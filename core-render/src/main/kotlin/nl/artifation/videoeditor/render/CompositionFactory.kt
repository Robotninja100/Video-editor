package nl.artifation.videoeditor.render

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import nl.artifation.videoeditor.model.RenderEffect
import nl.artifation.videoeditor.model.RenderItem
import nl.artifation.videoeditor.model.RenderPlan
import nl.artifation.videoeditor.model.RenderSequence
import kotlin.math.pow

/**
 * Vertaalt een [RenderPlan] naar een Media3-[Composition].
 *
 * Deze vertaling beslist bewust niets. Welke clips, welke trims, welke
 * effectvolgorde, en het omrekenen van bron- naar cliptijden: dat is allemaal al
 * gebeurd en getest in `:core-model`. Hier wordt alleen nog opgebouwd. De
 * vertaaltabel staat in `docs/PRODUCTPLAN.md` §"Spec voor :core-render"; elke regel
 * hieronder hoort één op één bij een regel daar.
 *
 * Twee waarden worden expliciet *niet* opnieuw afgeleid, omdat ze al in het plan
 * staan: [RenderEffect.MaskedBlur.sourceOffsetUs] en de tijden in
 * [RenderEffect.CaptionOverlays]. Zelf iets uit de clipping-configuratie afleiden
 * is precies de fout die pas op een getrimde clip zichtbaar wordt.
 */
public fun RenderPlan.toComposition(context: Context): Composition {
    require(sequences.isNotEmpty()) { "een renderplan zonder sequences valt niet te renderen" }

    return Composition.Builder(sequences.map { it.toEditedMediaItemSequence(context, frameRate) })
        // De uitvoerresolutie hangt in Media3 aan de compositie en niet aan de
        // Transformer: die heeft er geen setter voor. Presentation is het mechanisme
        // dat de checklist met "encoderinstellingen" bedoelt.
        .setEffects(
            Effects(
                /* audioProcessors= */ emptyList(),
                /* videoEffects= */
                listOf(
                    Presentation.createForWidthAndHeight(
                        width,
                        height,
                        Presentation.LAYOUT_SCALE_TO_FIT,
                    ),
                ),
            ),
        )
        .build()
}

private fun RenderSequence.toEditedMediaItemSequence(
    context: Context,
    frameRate: Int,
): EditedMediaItemSequence {
    // Beeld én geluid. Dit bepaalt ook wat een gat is: volgens Media3 krijgt een
    // gat de sporen van de sequence, dus zonder videospoor zou een gat aan het
    // begin geen zwart beeld opleveren maar helemaal niets.
    val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))

    items.forEach { item ->
        when (item) {
            is RenderItem.Gap -> builder.addGap(item.durationUs)
            is RenderItem.Source -> builder.addItem(item.toEditedMediaItem(context, frameRate))
        }
    }

    return builder.build()
}

private fun RenderItem.Source.toEditedMediaItem(
    context: Context,
    frameRate: Int,
): EditedMediaItem {
    val mediaItem = MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(clipStartUs)
                .setEndPositionUs(clipEndUs)
                .build(),
        )
        .build()

    val videoEffects = effects.flatMap { it.toVideoEffects(context) }

    return EditedMediaItem.Builder(mediaItem)
        // setSpeed en niet SpeedChangeEffect: dat effect is in 1.10.1 afgeschaft
        // ten gunste hiervan, en dit regelt beeld én geluid in één keer. Media3
        // verbiedt bovendien snelheidseffecten zodra een SpeedProvider gezet is.
        .apply { if (speed != 1f) setSpeed(ConstantSpeedProvider(speed)) }
        // Een bovengrens, geen doelwaarde: Media3 kan de framerate wel omlaag
        // brengen maar niet omhoog. Dat is precies wat hier nodig is, want een clip
        // die versneld wordt levert anders een absurd hoge framerate op.
        .setFrameRate(frameRate)
        // setDurationUs wordt bewust niet gezet. Media3 wil daar de duur van het
        // hele bronbestand hebben, vóór clipping en snelheid — en die staat niet in
        // het renderplan. Voor video met een eigen duur is het optioneel, dus een
        // gok is hier slechter dan weglaten.
        .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
        .build()
}

/**
 * Eén [RenderEffect] kan meer dan één Media3-effect opleveren: een kleurcorrectie
 * met zowel belichting als contrast is in Media3 twee aparte matrices.
 */
private fun RenderEffect.toVideoEffects(context: Context): List<Effect> = when (this) {
    // Het hele effect gaat mee, niet losse velden: de shader heeft `sourcePtsFor`
    // nodig, en die hoort bij het effect. Velden uitpakken en in de shader opnieuw
    // combineren is precies hoe de snelheidsfactor eerder verdween.
    is RenderEffect.MaskedBlur -> listOf(MaskedBlurEffect(this, context))

    is RenderEffect.CropPath -> listOf(CropPathEffect(keyframes))

    is RenderEffect.CaptionOverlays -> listOf(OverlayEffect(listOf(CaptionsOverlay(style, cues))))

    is RenderEffect.ColorAdjust -> buildList {
        if (exposure != 0f) {
            // Belichting in stops, zoals in de fotografie: +1 is twee keer zo veel licht.
            val scale = 2f.pow(exposure)
            add(
                RgbAdjustment.Builder()
                    .setRedScale(scale)
                    .setGreenScale(scale)
                    .setBlueScale(scale)
                    .build(),
            )
        }
        // Media3's Contrast verwacht -1..1; het model hanteert dezelfde schaal.
        if (contrast != 0f) add(Contrast(contrast.coerceIn(-1f, 1f)))
    }
}
