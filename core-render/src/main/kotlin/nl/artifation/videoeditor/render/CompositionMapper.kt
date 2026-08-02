package nl.artifation.videoeditor.render

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import nl.artifation.videoeditor.model.CompositionPlan
import nl.artifation.videoeditor.model.PlannedClip
import nl.artifation.videoeditor.model.PlannedEffect
import nl.artifation.videoeditor.model.PlannedGap
import nl.artifation.videoeditor.model.PlannedSequence

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
public class CompositionMapper(private val context: Context) {

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
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.inPointUs / 1_000)
                    .setEndPositionMs(clip.outPointUs / 1_000)
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
                            context = context,
                            maskUri = Uri.parse(effect.maskUri),
                            radiusFrac = effect.radiusFrac,
                            clip = clip,
                        ),
                    )

                    // Nog te implementeren; expliciet en niet stilzwijgend genegeerd,
                    // zodat een ontbrekend effect opvalt in plaats van te verdwijnen.
                    is PlannedEffect.AnimatedCrop -> TODO("keyframed crop — fase 5")
                    is PlannedEffect.Captions -> TODO("caption overlays — fase 3")
                    is PlannedEffect.ColorAdjust -> TODO("kleurcorrectie — fase 7")
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
            .setSpeed(clip.speed)
            .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
            .build()
    }
}
