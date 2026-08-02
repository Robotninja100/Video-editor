package nl.artifation.videoeditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wat er gerenderd moet worden, uitgerekend en klaar om te vertalen.
 *
 * Dit is de helft van `toComposition()` die géén Android nodig heeft. Die functie
 * doet namelijk twee dingen door elkaar: *beslissen* wat er gerenderd wordt — gaten,
 * trims, snelheid, effectvolgorde, en het omrekenen van brontijden naar cliptijden —
 * en *bouwen* van Media3-objecten. Alleen het tweede vereist een toestel.
 *
 * Door het eerste hierheen te halen is de riskante rekenkant volledig unittestbaar
 * op de JVM, en houdt `:core-render` een domme één-op-één-vertaling over. Zie
 * `docs/CHECKLIST.md` §"Spec voor :core-render" voor die vertaaltabel.
 *
 * Net als de rest van deze module: geen enkel Media3-type, alles in microseconden,
 * alle effectparameters genormaliseerd.
 */
@Serializable
public data class RenderPlan(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val sequences: List<RenderSequence>,
) {
    val durationUs: Us get() = sequences.maxOfOrNull { it.durationUs } ?: 0L
}

@Serializable
public data class RenderSequence(
    val id: String,
    val items: List<RenderItem> = emptyList(),
) {
    val durationUs: Us get() = items.sumOf { it.durationUs }
}

@Serializable
public sealed interface RenderItem {
    /** Positie op de tijdlijn, al uitgerekend zodat de renderer niet hoeft op te tellen. */
    public val timelineStartUs: Us
    public val durationUs: Us

    /** Leegte. Vertaalt naar `EditedMediaItemSequence.Builder.addGap()`. */
    @Serializable
    @SerialName("gap")
    public data class Gap(
        override val timelineStartUs: Us,
        override val durationUs: Us,
    ) : RenderItem

    @Serializable
    @SerialName("source")
    public data class Source(
        val id: String,
        val sourceUri: String,
        /** Snijpunten in de **bron**; vertaalt naar `ClippingConfiguration`. */
        val clipStartUs: Us,
        val clipEndUs: Us,
        val speed: Float,
        override val timelineStartUs: Us,
        /** Duur op de tijdlijn — snelheid is hier al in verwerkt. */
        override val durationUs: Us,
        /** Volgorde is renderVolgorde. */
        val effects: List<RenderEffect> = emptyList(),
    ) : RenderItem
}

/**
 * Effect met alle tijden al omgerekend naar **cliptijd**: nul is het eerste frame
 * dat de renderer te zien krijgt, en de snelheid is verrekend.
 *
 * De bron van de verwarring die dit wegneemt: analyses (whisper, tracking, reframe)
 * draaien op het hele bronbestand en leveren dus brontijden. De renderer ziet een
 * getrimd, mogelijk versneld fragment. Dat verschil één keer goed omrekenen, hier,
 * is beter dan het op vier plekken in de shaders opnieuw proberen.
 */
@Serializable
public sealed interface RenderEffect {

    /**
     * [sourceOffsetUs] is het in-punt van de clip. De maskdecoder trekt dit van de
     * frame-PTS af om te bepalen hoe ver hij vooruit moet trekken. Het staat hier
     * expliciet omdat afleiden uit de clipping-configuratie precies de fout is die
     * pas op een getrimde clip zichtbaar wordt — zie `docs/BOUWPLAN.md`.
     */
    @Serializable
    @SerialName("maskedBlur")
    public data class MaskedBlur(
        val maskUri: String,
        val radiusFrac: Float,
        val sourceOffsetUs: Us,
    ) : RenderEffect

    @Serializable
    @SerialName("crop")
    public data class CropPath(
        val keyframes: List<Keyframe<NormRect>>,
    ) : RenderEffect

    @Serializable
    @SerialName("captions")
    public data class CaptionOverlays(
        val style: CaptionStyle,
        val cues: List<Cue>,
    ) : RenderEffect

    @Serializable
    @SerialName("colorAdjust")
    public data class ColorAdjust(
        val exposure: Float,
        val contrast: Float,
    ) : RenderEffect
}

/** Vertaalt het project naar een renderplan. Het enige koppelvlak richting `:core-render`. */
public fun Project.toRenderPlan(): RenderPlan = RenderPlan(
    width = outputSpec.width,
    height = outputSpec.height,
    frameRate = outputSpec.frameRate,
    sequences = sequences.map { it.toRenderSequence() },
)

public fun Sequence.toRenderSequence(): RenderSequence {
    val starts = itemStartsUs()
    return RenderSequence(
        id = id,
        items = items.mapIndexed { index, item ->
            when (item) {
                is Gap -> RenderItem.Gap(
                    timelineStartUs = starts[index],
                    durationUs = item.durationUs,
                )

                is Clip -> RenderItem.Source(
                    id = item.id,
                    sourceUri = item.sourceUri,
                    clipStartUs = item.inPointUs,
                    clipEndUs = item.outPointUs,
                    speed = item.speed,
                    timelineStartUs = starts[index],
                    // Niet herberekenen: Clip.durationUs is de enige waarheid over
                    // hoeveel tijdlijnruimte een clip inneemt.
                    durationUs = item.durationUs,
                    effects = item.effects.mapNotNull { it.toRenderEffect(item) },
                )
            }
        },
    )
}

/**
 * Brontijd naar cliptijd.
 *
 * Bewust dezelfde `Float`-deling als [Clip.durationUs], zodat het uitpunt exact op
 * de clipduur uitkomt. Een "nauwkeurigere" `Double`-deling zou hier een verschil van
 * één microseconde kunnen opleveren met de duur die de rest van het model gebruikt,
 * en dat is erger dan de afrondingsfout zelf.
 */
private fun Clip.sourceToClipUs(sourceUs: Us): Us = ((sourceUs - inPointUs) / speed).toLong()

/** Geeft `null` voor effecten die niets doen — dat houdt renderplannen leesbaar. */
private fun EffectSpec.toRenderEffect(clip: Clip): RenderEffect? = when (this) {
    is EffectSpec.MaskedBlur ->
        if (radiusFrac <= 0f) {
            null
        } else {
            RenderEffect.MaskedBlur(
                maskUri = maskUri,
                radiusFrac = radiusFrac,
                sourceOffsetUs = clip.inPointUs,
            )
        }

    is EffectSpec.Crop ->
        if (path.isEmpty()) null else RenderEffect.CropPath(path.toClipPath(clip))

    is EffectSpec.Captions ->
        cues.mapNotNull { it.toClipCue(clip) }
            .takeIf { it.isNotEmpty() }
            ?.let { RenderEffect.CaptionOverlays(style, it) }

    is EffectSpec.ColorAdjust ->
        if (exposure == 0f && contrast == 0f) {
            null
        } else {
            RenderEffect.ColorAdjust(exposure, contrast)
        }
}

/**
 * Snijdt een crop-pad bij op het clipvenster en zet het om naar cliptijd.
 *
 * De randen worden geïnterpoleerd in plaats van weggegooid: zonder keyframe op tijd
 * nul zou de crop bij het eerste frame naar de eerstvolgende waarde springen.
 */
private fun List<Keyframe<NormRect>>.toClipPath(clip: Clip): List<Keyframe<NormRect>> {
    val start = interpolateAt(clip.inPointUs) ?: return emptyList()
    val end = interpolateAt(clip.outPointUs) ?: return emptyList()

    val inner = filter { it.atUs > clip.inPointUs && it.atUs < clip.outPointUs }
    val bounded = listOf(Keyframe(clip.inPointUs, start)) + inner + Keyframe(clip.outPointUs, end)

    return bounded.map { Keyframe(clip.sourceToClipUs(it.atUs), it.value) }
}

/**
 * Zet één cue om naar cliptijd, of gooit hem weg als hij buiten de clip valt.
 *
 * Cues die de rand overlappen worden afgekapt: de tekst hoort bij het beeld dat er
 * nog wél is. Woordtijden gaan door dezelfde behandeling, zodat karaoke-highlighting
 * op een getrimde clip niet uit de pas loopt.
 */
private fun Cue.toClipCue(clip: Clip): Cue? {
    val clampedStart = startUs.coerceAtLeast(clip.inPointUs)
    val clampedEnd = endUs.coerceAtMost(clip.outPointUs)
    if (clampedEnd <= clampedStart) return null

    return Cue(
        startUs = clip.sourceToClipUs(clampedStart),
        endUs = clip.sourceToClipUs(clampedEnd),
        text = text,
        words = words.mapNotNull { word ->
            val wordStart = word.startUs.coerceAtLeast(clampedStart)
            val wordEnd = word.endUs.coerceAtMost(clampedEnd)
            if (wordEnd <= wordStart) {
                null
            } else {
                Cue.Word(
                    startUs = clip.sourceToClipUs(wordStart),
                    endUs = clip.sourceToClipUs(wordEnd),
                    text = word.text,
                )
            }
        },
    )
}
