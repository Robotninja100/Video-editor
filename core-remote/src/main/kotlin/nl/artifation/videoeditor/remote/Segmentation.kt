package nl.artifation.videoeditor.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.artifation.videoeditor.model.NormRect

/**
 * Segmentatie en tracking via SAM 3.
 *
 * De dienst krijgt een **proxy** van de video (bijvoorbeeld 540p) en een prompt —
 * een tik van de gebruiker, een box, of tekst — en levert per frame een mask.
 * Nooit het 4K-origineel uploaden: upload is de bottleneck, niet de inferentie,
 * en de masks worden toch op halve resolutie opgeslagen.
 */
public object Segmentation {

    /** De dienst rekent per blok van zestien frames af, niet per frame. */
    private const val FRAMES_PER_BILLING_UNIT = 16.0

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Waar de gebruiker het object mee aanwijst. */
    public sealed interface Prompt {
        /** Tik op het object; genormaliseerde coördinaten. */
        public data class Point(val x: Float, val y: Float, val positive: Boolean = true) : Prompt

        public data class Box(val rect: NormRect) : Prompt

        /** Tekstprompt, bijvoorbeeld "gezicht" of "kenteken". */
        public data class Text(val query: String) : Prompt
    }

    public data class Request(
        val videoUrl: String,
        val prompts: List<Prompt>,
        /**
         * Framerate waarop getrackt wordt. Lager is goedkoper: de kosten schalen
         * lineair met het aantal frames. Blur-randen zijn zacht, dus 10 fps met
         * interpolatie ertussen is visueel niet te onderscheiden van 30 fps.
         */
        val trackingFps: Int = 10,
    ) {
        init {
            require(videoUrl.isNotBlank()) { "videoUrl mag niet leeg zijn" }
            require(trackingFps > 0) { "trackingFps moet positief zijn" }
        }

        /** Geschatte kosten, om de gebruiker vooraf te kunnen waarschuwen. */
        public fun estimatedUsd(durationSeconds: Double, usdPer16Frames: Double = 0.005): Double {
            val frames = durationSeconds * trackingFps
            return frames / FRAMES_PER_BILLING_UNIT * usdPer16Frames
        }
    }

    @Serializable
    private data class ApiResponse(val masks: List<ApiMask> = emptyList())

    @Serializable
    private data class ApiMask(
        val frame: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        /**
         * `[hoogte, breedte]`, zoals het gangbare COCO-RLE-formaat het levert.
         *
         * Zonder dit veld leverde een antwoord dat de afmetingen zó opgeeft een
         * mask van nul bytes op: `width` en `height` bleven op hun default 0,
         * `ignoreUnknownKeys` slikte `size`, en `decodeRle(counts, 0)` gaf braaf
         * een lege array terug. Geen fout, geen waarschuwing — de blur deed
         * alleen niets meer.
         */
        val size: List<Int> = emptyList(),
        /** COCO-stijl RLE: afwisselend aantal nullen en enen, rijgewijs. */
        val counts: List<Int> = emptyList(),
    ) {
        /** `width`/`height` als de dienst ze los meestuurt, anders uit `size`. */
        val resolvedWidth: Int get() = if (width > 0) width else size.getOrElse(1) { 0 }
        val resolvedHeight: Int get() = if (height > 0) height else size.getOrElse(0) { 0 }
    }

    public data class MaskFrame(
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        /** Eén byte per pixel: 0 of 255. Klaar om als grayscale frame te encoderen. */
        val pixels: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is MaskFrame &&
                frameIndex == other.frameIndex &&
                width == other.width &&
                height == other.height &&
                pixels.contentEquals(other.pixels)

        override fun hashCode(): Int =
            (frameIndex * 31 + width) * 31 + height + pixels.contentHashCode()
    }

    /**
     * Frames met onmogelijke afmetingen worden overgeslagen, niet gegooid.
     *
     * Eén negatieve breedte liet eerder `require` in [decodeRle] klappen en nam
     * daarmee de masks van álle frames mee — terwijl dezelfde module bij een
     * verkeerd getelde run juist afkapt in plaats van te mislukken, en
     * [AutoEdit] bij dit soort invoer wél defensief is. Twee negatieve
     * afmetingen gaven bovendien een positief product, en 65536×65536 liep over
     * naar exact nul: allebei een MaskFrame dat er geloofwaardig uitziet en niets
     * bevat.
     */
    public fun parse(body: String): List<MaskFrame> =
        json.decodeFromString<ApiResponse>(body).masks.mapNotNull { mask ->
            val width = mask.resolvedWidth
            val height = mask.resolvedHeight
            val totalPixels = width.toLong() * height
            if (width <= 0 || height <= 0 || totalPixels > MAX_PIXELS) {
                null
            } else {
                MaskFrame(
                    frameIndex = mask.frame,
                    width = width,
                    height = height,
                    pixels = decodeRle(mask.counts, totalPixels.toInt()),
                )
            }
        }

    /**
     * Bovengrens per mask: 8K bij 8K.
     *
     * Ruim boven alles wat een telefoon opneemt, en ver onder de grens waar
     * `width * height` als Int overloopt of waar een enkele allocatie het
     * geheugen opeet.
     */
    private const val MAX_PIXELS: Long = 8192L * 8192L

    /**
     * Decodeert COCO-stijl RLE naar een bytemask.
     *
     * De reeks telt afwisselend achtergrond en voorgrond, beginnend bij
     * achtergrond. Een run die voorbij het einde loopt wordt afgekapt in plaats
     * van te crashen — een dienst die één pixel te veel telt mag de hele
     * analyse niet laten mislukken.
     */
    internal fun decodeRle(counts: List<Int>, totalPixels: Int): ByteArray {
        require(totalPixels >= 0) { "totalPixels moet >= 0 zijn" }
        val out = ByteArray(totalPixels)
        var offset = 0
        var value: Byte = 0

        for (run in counts) {
            if (offset >= totalPixels) break
            val length = minOf(run.coerceAtLeast(0), totalPixels - offset)
            if (value != 0.toByte()) {
                java.util.Arrays.fill(out, offset, offset + length, value)
            }
            offset += length
            value = if (value == 0.toByte()) 255.toByte() else 0
        }
        return out
    }
}

/**
 * Vult ontbrekende frames op wanneer er op lagere framerate getrackt is.
 *
 * Er wordt niet geïnterpoleerd tussen maskvormen — dat geeft rare tussenvormen.
 * In plaats daarvan wordt het dichtstbijzijnde mask vastgehouden. Bij zachte
 * blur-randen is dat visueel niet te zien en het is wél voorspelbaar.
 */
public object MaskInterpolation {

    public fun expand(
        masks: List<Segmentation.MaskFrame>,
        targetFrameCount: Int,
        trackedFps: Int,
        targetFps: Int,
    ): List<Segmentation.MaskFrame> {
        require(trackedFps > 0 && targetFps > 0) { "framerates moeten positief zijn" }
        if (masks.isEmpty() || targetFrameCount <= 0) return emptyList()

        val byIndex = masks.associateBy { it.frameIndex }
        val ratio = trackedFps.toDouble() / targetFps

        return (0 until targetFrameCount).map { target ->
            val sourceIndex = (target * ratio).toInt()
            val nearest = byIndex[sourceIndex]
                ?: masks.minBy { kotlin.math.abs(it.frameIndex - sourceIndex) }
            nearest.copy(frameIndex = target)
        }
    }
}
