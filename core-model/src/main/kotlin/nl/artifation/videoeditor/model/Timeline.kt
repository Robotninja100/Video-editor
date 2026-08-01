package nl.artifation.videoeditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Alle tijden in dit model zijn microseconden.
 *
 * Nooit framenummers: telefoonopnames zijn vaak variable framerate, waardoor
 * `frame / fps` niet klopt. Microseconden zijn de enige betrouwbare eenheid.
 */
public typealias Us = Long

public const val US_PER_MS: Us = 1_000L
public const val US_PER_SECOND: Us = 1_000_000L

/**
 * Genormaliseerd rechthoekje in de eenheidsruimte (0..1), niet in pixels.
 *
 * Resolutie-onafhankelijk, zodat preview (vaak op lagere resolutie) en export
 * hetzelfde resultaat geven. Zie ook [EffectSpec.MaskedBlur.radiusFrac].
 */
@Serializable
public data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    public companion object {
        public val FULL: NormRect = NormRect(0f, 0f, 1f, 1f)
    }
}

@Serializable
public data class Keyframe<T>(
    val atUs: Us,
    val value: T,
)

@Serializable
public data class CaptionStyle(
    val fontSizeFrac: Float = 0.05f,
    val fillArgb: Int = 0xFFFFFFFF.toInt(),
    val outlineArgb: Int = 0xFF000000.toInt(),
    val outlineWidthFrac: Float = 0.004f,
    val bottomMarginFrac: Float = 0.12f,
)

/** Eén ondertitelregel met woordgrenzen, zodat karaoke-highlighting mogelijk blijft. */
@Serializable
public data class Cue(
    val startUs: Us,
    val endUs: Us,
    val text: String,
    val words: List<Word> = emptyList(),
) {
    @Serializable
    public data class Word(val startUs: Us, val endUs: Us, val text: String)
}

/**
 * Serialiseerbare effectbeschrijving. Bevat bewust **geen** Media3-objecten:
 * de vertaling naar Media3 gebeurt in `:core-render`, achter één functie.
 * Zolang dat het enige koppelvlak is, kan Media3 geüpgraded worden zonder
 * dit model aan te raken — relevant, want `CompositionPlayer` is experimenteel.
 */
@Serializable
public sealed interface EffectSpec {

    /**
     * Blur buiten de mask, scherp binnen de mask.
     *
     * [radiusFrac] is een fractie van de framebreedte, **geen pixels**. Een vaste
     * pixelradius ziet er in preview (lagere resolutie) anders uit dan in de export.
     */
    @Serializable
    @SerialName("maskedBlur")
    public data class MaskedBlur(
        val maskUri: String,
        val radiusFrac: Float = 0.02f,
    ) : EffectSpec

    /** Crop-pad voor auto-reframe; leeg pad betekent geen crop. */
    @Serializable
    @SerialName("crop")
    public data class Crop(
        val path: List<Keyframe<NormRect>> = emptyList(),
    ) : EffectSpec

    @Serializable
    @SerialName("captions")
    public data class Captions(
        val style: CaptionStyle = CaptionStyle(),
        val cues: List<Cue> = emptyList(),
    ) : EffectSpec

    @Serializable
    @SerialName("colorAdjust")
    public data class ColorAdjust(
        val exposure: Float = 0f,
        val contrast: Float = 0f,
    ) : EffectSpec
}

/**
 * Eén positie op de tijdlijn. Items binnen een [Sequence] overlappen nooit —
 * dat is een harde Media3-beperking (`EditedMediaItemSequence`). Overlappende
 * beelden vereisen een tweede sequence.
 */
@Serializable
public sealed interface TimelineItem {
    public val durationUs: Us
}

/** Leegte op de tijdlijn. Vertaalt naar `EditedMediaItemSequence.Builder.addGap()`. */
@Serializable
@SerialName("gap")
public data class Gap(
    override val durationUs: Us,
) : TimelineItem

@Serializable
@SerialName("clip")
public data class Clip(
    val id: String,
    /** Bewust een String en geen `android.net.Uri`, zodat deze module pure JVM blijft. */
    val sourceUri: String,
    val inPointUs: Us,
    val outPointUs: Us,
    val speed: Float = 1f,
    val effects: List<EffectSpec> = emptyList(),
) : TimelineItem {

    /** Duur van het gebruikte stuk bronmateriaal, vóór snelheidsaanpassing. */
    val sourceDurationUs: Us get() = outPointUs - inPointUs

    /**
     * Duur op de tijdlijn. Snelheid telt mee: een clip op 2x neemt de helft
     * van de tijdlijnruimte in.
     */
    override val durationUs: Us get() = (sourceDurationUs / speed).toLong()
}

@Serializable
public data class Sequence(
    val id: String,
    val items: List<TimelineItem> = emptyList(),
) {
    val durationUs: Us get() = items.sumOf { it.durationUs }

    /** Starttijd van elk item, in dezelfde volgorde als [items]. */
    public fun itemStartsUs(): List<Us> {
        var acc = 0L
        return items.map { val start = acc; acc += it.durationUs; start }
    }

    /** Het item dat [timelineUs] overlapt, plus de offset binnen dat item. */
    public fun locate(timelineUs: Us): Located? {
        if (timelineUs < 0) return null
        var acc = 0L
        for ((index, item) in items.withIndex()) {
            val end = acc + item.durationUs
            if (timelineUs < end) return Located(index, item, timelineUs - acc)
            acc = end
        }
        return null
    }

    public data class Located(val index: Int, val item: TimelineItem, val offsetUs: Us)
}

@Serializable
public data class OutputSpec(
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
) {
    val aspect: Float get() = width.toFloat() / height.toFloat()
}

@Serializable
public data class Project(
    val id: String,
    /** Index 0 is de hoofdtrack; de rest zijn overlays. */
    val sequences: List<Sequence>,
    val outputSpec: OutputSpec = OutputSpec(),
) {
    val mainSequence: Sequence get() = sequences.first()

    /** Projectduur = de langste sequence. */
    val durationUs: Us get() = sequences.maxOfOrNull { it.durationUs } ?: 0L
}
