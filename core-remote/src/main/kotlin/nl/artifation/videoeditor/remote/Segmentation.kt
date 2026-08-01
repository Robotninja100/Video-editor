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
            return frames / 16.0 * usdPer16Frames
        }
    }

    @Serializable
    private data class ApiResponse(val masks: List<ApiMask> = emptyList())

    @Serializable
    private data class ApiMask(
        val frame: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        /** COCO-stijl RLE: afwisselend aantal nullen en enen, rijgewijs. */
        val counts: List<Int> = emptyList(),
    )

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

    public fun parse(body: String): List<MaskFrame> =
        json.decodeFromString<ApiResponse>(body).masks.map { mask ->
            MaskFrame(
                frameIndex = mask.frame,
                width = mask.width,
                height = mask.height,
                pixels = decodeRle(mask.counts, mask.width * mask.height),
            )
        }

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
