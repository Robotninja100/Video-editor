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
 * `docs/PRODUCTPLAN.md` §"Spec voor :core-render" voor die vertaaltabel.
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
     * Blur buiten de mask, scherp binnen de mask.
     *
     * De maskdecoder moet per frame weten wélk maskframe erbij hoort. Dat is geen
     * optelling maar een omrekening met twee termen, en beide zijn eerder fout
     * gegaan — vandaar [sourcePtsFor], hier, met tests eromheen in plaats van in
     * een shader die alleen op een toestel draait.
     */
    @Serializable
    @SerialName("maskedBlur")
    public data class MaskedBlur(
        val maskUri: String,
        val radiusFrac: Float,
        /**
         * Het in-punt van de clip in de bron. Staat hier expliciet omdat afleiden
         * uit de clipping-configuratie precies de fout is die pas op een getrimde
         * clip zichtbaar wordt — zie `docs/BOUWPLAN.md`.
         */
        val sourceOffsetUs: Us,
        /** Het uit-punt, om niet voorbij het einde van de mask te trekken. */
        val sourceEndUs: Us,
        /** De snelheid van de clip; zie [sourcePtsFor]. */
        val speed: Float = 1f,
    ) : RenderEffect {

        /**
         * Cliptijd → positie in het bronmateriaal, voor de masktrack.
         *
         * Twee dingen zitten hierin:
         *
         * 1. **Het in-punt.** Sidecars horen bij de bronclip, niet bij de tijdlijn.
         *    Een clip die op 5 s begint, heeft op cliptijd 0 de mask van 5 s nodig.
         * 2. **De snelheid.** Op 2× hoort cliptijd 5 s bij bronpositie 10 s. Zonder
         *    deze factor loopt de mask lineair weg van waar hij hoort — na vijf
         *    seconden zit de blur vijf seconden naast het onderwerp.
         *
         * **Nog te bewijzen op een toestel.** Of Media3 de effect-PTS vóór of ná de
         * snelheidsaanpassing aanlevert, bepaalt of hier vermenigvuldigd of gedeeld
         * moet worden. Dat hoort bij fase 0, samen met het teken van het in-punt en
         * de preview/export-pariteit. Blijkt het andersom, dan is dit één regel —
         * en dat is precies waarom hij hier staat en niet in de shader.
         */
        public fun sourcePtsFor(clipPtsUs: Us): Us {
            val offsetUs = (clipPtsUs.coerceAtLeast(0L) * speed).toLong()
            // Nooit voorbij het einde van de clip: daar staat geen mask meer, en de
            // decoder zou dan tot het einde van het bestand doorspoelen.
            return (sourceOffsetUs + offsetUs).coerceIn(sourceOffsetUs, sourceEndUs)
        }
    }

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

/**
 * Middelpunt van deze rechthoek in Media3's normalized device coordinates.
 *
 * Media3 rekent in −1..1 met de oorsprong in het midden en de y-as naar boven; het
 * projectmodel in 0..1 met de oorsprong linksboven en de y-as naar beneden. Die
 * omrekening — vooral de y-flip — is het soort fout dat je op een toestel uren kost
 * en hier in één test vastligt. Vandaar dat hij hier staat en niet in `:core-render`.
 */
public fun NormRect.ndcCenter(): Pair<Float, Float> = (centerX * 2f - 1f) to (1f - centerY * 2f)

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
                sourceEndUs = clip.outPointUs,
                speed = clip.speed,
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
