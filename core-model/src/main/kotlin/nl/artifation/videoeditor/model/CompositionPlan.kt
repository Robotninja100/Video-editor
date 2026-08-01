package nl.artifation.videoeditor.model

/**
 * Het rendercontract tussen het projectmodel en Media3.
 *
 * Dit is bewust een tussenstap. `Project` beschrijft wat de gebruiker bedoelt;
 * `CompositionPlan` beschrijft precies wat de renderlaag moet bouwen, met alle
 * rekenwerk al gedaan — coördinaten omgerekend, effecten geordend, snelheid
 * verdisconteerd. `:core-render` doet daarna alleen nog een mechanische vertaling
 * naar Media3-objecten.
 *
 * De winst: al het foutgevoelige rekenwerk staat in pure code die hier getest
 * wordt, in plaats van in een Android-module die alleen op een toestel draait.
 */
public data class CompositionPlan(
    val sequences: List<PlannedSequence>,
    val outputSpec: OutputSpec,
) {
    val durationUs: Us get() = sequences.maxOfOrNull { it.durationUs } ?: 0L
}

public data class PlannedSequence(
    val id: String,
    val items: List<PlannedItem>,
) {
    val durationUs: Us get() = items.sumOf { it.durationUs }
}

public sealed interface PlannedItem {
    public val durationUs: Us
}

/** Vertaalt naar `EditedMediaItemSequence.Builder.addGap()`. */
public data class PlannedGap(override val durationUs: Us) : PlannedItem

/** Vertaalt naar één `EditedMediaItem`. */
public data class PlannedClip(
    val sourceUri: String,
    val inPointUs: Us,
    val outPointUs: Us,
    val speed: Float,
    val effects: List<PlannedEffect>,
    override val durationUs: Us,
) : PlannedItem

public sealed interface PlannedEffect {

    /**
     * Crop in Media3's normalized device coordinates.
     *
     * Media3 werkt in NDC: −1..1 met de oorsprong in het midden en de y-as naar
     * boven. Het projectmodel werkt in 0..1 met de oorsprong linksboven en de
     * y-as naar beneden. Die omrekening — inclusief de y-flip — is precies het
     * soort fout dat je op een toestel uren kost en hier in één test vastligt.
     */
    public data class Crop(
        val left: Float,
        val right: Float,
        val bottom: Float,
        val top: Float,
    ) : PlannedEffect

    /** Crop met keyframes; de renderlaag interpoleert per frame. */
    public data class AnimatedCrop(val path: List<Keyframe<Crop>>) : PlannedEffect

    public data class MaskedBlur(val maskUri: String, val radiusFrac: Float) : PlannedEffect

    public data class Captions(val style: CaptionStyle, val cues: List<Cue>) : PlannedEffect

    public data class ColorAdjust(val exposure: Float, val contrast: Float) : PlannedEffect
}

/**
 * Vertaalt een project naar een renderplan.
 *
 * Dit is het enige koppelvlak richting Media3. Zolang het geïsoleerd blijft, kan
 * Media3 geüpgraded worden zonder de editor te herschrijven — relevant, want
 * `CompositionPlayer` is nog experimenteel en gaat schuiven.
 */
public fun Project.toCompositionPlan(): CompositionPlan = CompositionPlan(
    sequences = sequences.map { sequence ->
        PlannedSequence(
            id = sequence.id,
            items = sequence.items.map { it.toPlannedItem() },
        )
    },
    outputSpec = outputSpec,
)

private fun TimelineItem.toPlannedItem(): PlannedItem = when (this) {
    is Gap -> PlannedGap(durationUs)
    is Clip -> PlannedClip(
        sourceUri = sourceUri,
        inPointUs = inPointUs,
        outPointUs = outPointUs,
        speed = speed,
        effects = effects.map { it.toPlannedEffect() },
        durationUs = durationUs,
    )
}

private fun EffectSpec.toPlannedEffect(): PlannedEffect = when (this) {
    is EffectSpec.MaskedBlur -> PlannedEffect.MaskedBlur(maskUri, radiusFrac)
    is EffectSpec.Captions -> PlannedEffect.Captions(style, cues)
    is EffectSpec.ColorAdjust -> PlannedEffect.ColorAdjust(exposure, contrast)
    is EffectSpec.Crop -> when (path.size) {
        0 -> PlannedEffect.Crop(left = -1f, right = 1f, bottom = -1f, top = 1f)
        1 -> path.single().value.toNdcCrop()
        else -> PlannedEffect.AnimatedCrop(path.map { Keyframe(it.atUs, it.value.toNdcCrop()) })
    }
}

/**
 * 0..1 met oorsprong linksboven → −1..1 met oorsprong in het midden en y omhoog.
 */
public fun NormRect.toNdcCrop(): PlannedEffect.Crop = PlannedEffect.Crop(
    left = left * 2f - 1f,
    right = right * 2f - 1f,
    // y-flip: top in het model (0 = boven) wordt +1 in NDC.
    top = 1f - top * 2f,
    bottom = 1f - bottom * 2f,
)
