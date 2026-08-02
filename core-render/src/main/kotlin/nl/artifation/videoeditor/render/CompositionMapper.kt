package nl.artifation.videoeditor.render

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import nl.artifation.videoeditor.model.CompositionPlan
import nl.artifation.videoeditor.model.PlannedClip
import nl.artifation.videoeditor.model.PlannedEffect
import nl.artifation.videoeditor.model.PlannedGap
import nl.artifation.videoeditor.model.PlannedSequence
import nl.artifation.videoeditor.model.US_PER_MS
import kotlin.math.pow

/**
 * Het enige koppelvlak met Media3.
 *
 * Bewust mechanisch: al het rekenwerk — NDC-omrekening, snelheid, effectvolgorde —
 * is al gedaan in `Project.toCompositionPlan()`, dat pure code is en getest wordt
 * zonder toestel. Hier wordt alleen nog vertaald.
 *
 * Zolang deze klasse het enige aanrakingspunt blijft, kan Media3 geüpgraded of
 * vervangen worden zonder de editor te herschrijven. Dat is geen theorie:
 * `CompositionPlayer` is nog `@ExperimentalApi` en gaat schuiven.
 *
 * **Niet gecompileerd.** Geen Android SDK beschikbaar in de omgeving waarin dit
 * geschreven is; deze module staat nog niet in `settings.gradle.kts`.
 */
@UnstableApi
public class CompositionMapper {

    public fun map(plan: CompositionPlan): Composition {
        val sequences = plan.sequences.map { mapSequence(it, plan) }
        return Composition.Builder(sequences).build()
    }

    private fun mapSequence(sequence: PlannedSequence, plan: CompositionPlan): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()

        for (item in sequence.items) {
            when (item) {
                // Gaten zijn eersteklas in Media3 sinds 1.8; daarom mag het
                // projectmodel ze bevatten en hoeft "verplaatsen" geen trucs.
                is PlannedGap -> builder.addGap(item.durationUs)
                is PlannedClip -> builder.addItem(mapClip(item, plan))
            }
        }
        return builder.build()
    }

    private fun mapClip(clip: PlannedClip, plan: CompositionPlan): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            // Media3 knipt in milliseconden, dit project rekent in microseconden.
            // Dat is de enige plek waar precisie verloren gaat, en dat is niet te
            // vermijden: `ClippingConfiguration` kent geen fijnere eenheid. Het
            // beginpunt wordt naar beneden afgerond en het eindpunt naar boven,
            // zodat er hooguit een beeld te véél in zit en nooit te weinig.
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.inPointUs / US_PER_MS)
                    .setEndPositionMs((clip.outPointUs + US_PER_MS - 1) / US_PER_MS)
                    .build(),
            )
            .build()

        val videoEffects = buildList {
            for (effect in clip.effects) {
                when (effect) {
                    is PlannedEffect.Crop -> add(
                        Crop(effect.left, effect.right, effect.bottom, effect.top),
                    )

                    is PlannedEffect.MaskedBlur -> add(
                        MaskedBlurEffect(
                            maskUri = Uri.parse(effect.maskUri),
                            radiusFrac = effect.radiusFrac,
                            clip = clip,
                        ),
                    )

                    is PlannedEffect.AnimatedCrop -> add(
                        AnimatedCropEffect(path = effect.path, clip = clip),
                    )

                    is PlannedEffect.Captions -> add(
                        OverlayEffect(
                            // Expliciet getypeerd: `ImmutableList.of(overlay)` zou
                            // een lijst van de subklasse opleveren, en Java-generics
                            // accepteren die niet waar een lijst van de interface staat.
                            ImmutableList.of<TextureOverlay>(
                                CaptionOverlay(
                                    cues = effect.cues,
                                    style = effect.style,
                                    clip = clip,
                                    frameWidth = plan.outputSpec.width,
                                    frameHeight = plan.outputSpec.height,
                                ),
                            ),
                        ),
                    )

                    is PlannedEffect.ColorAdjust -> addAll(colorEffects(effect))
                }
            }

            // Altijd als laatste: de uitvoerresolutie legt het eindformaat vast.
            add(
                Presentation.createForWidthAndHeight(
                    plan.outputSpec.width,
                    plan.outputSpec.height,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
        }

        return EditedMediaItem.Builder(mediaItem)
            .setSpeed(speedProviderFor(clip.speed))
            .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
            .build()
    }

    /**
     * Belichting en contrast als twee losse effecten.
     *
     * Media3 heeft geen enkel effect dat beide doet, en dat is maar goed ook: een
     * effect dat niets verandert hoort er niet te staan. Een lege lijst laat het
     * beeld dus letterlijk met rust.
     *
     * Belichting wordt in stops uitgedrukt, zoals in een camera: +1 is twee keer
     * zoveel licht. Dat is een vermenigvuldiging van de kanalen, niet een optelling
     * — die zou de zwarten grijs maken.
     */
    private fun colorEffects(effect: PlannedEffect.ColorAdjust): List<Effect> = buildList {
        if (effect.exposure != 0f) {
            val factor = STOP_BASE.pow(effect.exposure)
            add(
                RgbAdjustment.Builder()
                    .setRedScale(factor)
                    .setGreenScale(factor)
                    .setBlueScale(factor)
                    .build(),
            )
        }
        if (effect.contrast != 0f) {
            add(Contrast(effect.contrast))
        }
    }

    /**
     * `setSpeed` neemt geen getal maar een [SpeedProvider].
     *
     * Media3 staat een snelheidsverloop bínnen één clip toe en vraagt daarom per
     * tijdstip om een waarde. Het projectmodel kent alleen een vaste snelheid per
     * clip, dus dit is de hele vertaling. Bij snelheid 1 gaat de meegeleverde
     * `DEFAULT` mee, zodat Media3 kan zien dat er niets te doen valt.
     */
    private fun speedProviderFor(speed: Float): SpeedProvider =
        if (speed == 1f) {
            SpeedProvider.DEFAULT
        } else {
            object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
            }
        }

    private companion object {
        /** Eén stop belichting is een verdubbeling. */
        const val STOP_BASE = 2f
    }
}
