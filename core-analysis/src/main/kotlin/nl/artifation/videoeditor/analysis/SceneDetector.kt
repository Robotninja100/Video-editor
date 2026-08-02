package nl.artifation.videoeditor.analysis

import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.Us
import kotlin.math.abs

/**
 * Samenvatting van één frame: een genormaliseerd helderheidshistogram.
 *
 * Een histogram in plaats van de pixels zelf, omdat scenedetectie ongevoelig moet
 * zijn voor beweging binnen het beeld — iemand die door het frame loopt is geen cut,
 * een andere camerahoek wel. Dat is precies het verschil tussen "waar zit het licht"
 * en "hoeveel licht is er".
 *
 * Het decoderen van frames hoort op Android; wat je ermee besluit hoort hier.
 */
@Serializable
public data class FrameSignature(
    val atUs: Us,
    val histogram: List<Float>,
) {
    init {
        require(histogram.isNotEmpty()) { "histogram mag niet leeg zijn" }
    }

    public companion object {

        /**
         * Bouwt een signatuur uit een luma-vlak (het Y-kanaal van een YUV-frame).
         *
         * @param luma één byte per pixel, unsigned 0..255
         */
        public fun fromLuma(atUs: Us, luma: ByteArray, bins: Int = DEFAULT_BINS): FrameSignature {
            require(bins > 0) { "bins moet positief zijn" }
            require(luma.isNotEmpty()) { "luma mag niet leeg zijn" }

            val counts = IntArray(bins)
            for (byte in luma) {
                val value = byte.toInt() and 0xFF
                counts[value * bins / 256]++
            }
            return FrameSignature(atUs, counts.map { it.toFloat() / luma.size })
        }

        public const val DEFAULT_BINS: Int = 32
    }
}

public data class SceneConfig(
    /** Afstand tussen twee opeenvolgende histogrammen waarboven het een cut heet. */
    val threshold: Float = 0.35f,
    /** Ondergrens voor scenelengte; houdt een flits of een lichtwissel bij elkaar. */
    val minSceneMs: Int = 400,
) {
    init {
        require(threshold in 0f..1f) { "threshold hoort tussen 0 en 1 te liggen, was $threshold" }
        require(minSceneMs >= 0) { "minSceneMs moet >= 0 zijn" }
    }
}

/**
 * Scenegrenzen uit een reeks frame-signaturen.
 *
 * Wordt op twee plekken gebruikt: auto-reframe mag de crop op een cut hard laten
 * verspringen in plaats van eroverheen te zwiepen, en tracking kan er zijn masker
 * mee opnieuw laten beginnen.
 */
public object SceneDetector {

    /**
     * @param frames oplopend in tijd, allemaal met evenveel bins
     * @return het tijdstip van het eerste frame van elke nieuwe scene; het begin
     *   van de opname staat er niet bij — dat is geen cut maar het begin.
     */
    public fun detect(
        frames: List<FrameSignature>,
        config: SceneConfig = SceneConfig(),
    ): List<Us> {
        if (frames.size < 2) return emptyList()
        require(frames.all { it.histogram.size == frames.first().histogram.size }) {
            "alle signaturen moeten evenveel bins hebben"
        }
        require(frames.zipWithNext().all { (a, b) -> b.atUs > a.atUs }) {
            "frames moeten oplopend in tijd staan"
        }

        val minSceneUs = config.minSceneMs.toLong() * US_PER_MS
        val cuts = mutableListOf<Us>()
        var sceneStartUs = frames.first().atUs

        for ((previous, current) in frames.zipWithNext()) {
            if (current.atUs - sceneStartUs < minSceneUs) continue
            if (distance(previous, current) <= config.threshold) continue

            cuts.add(current.atUs)
            sceneStartUs = current.atUs
        }
        return cuts
    }

    /**
     * Totale-variatieafstand tussen twee histogrammen: 0 is identiek, 1 is geen
     * enkele overlap. De helft van de som van de absolute verschillen, want elk
     * verschil telt twee keer mee — één keer waar het weggaat, één keer waar het
     * bij komt.
     */
    public fun distance(a: FrameSignature, b: FrameSignature): Float {
        require(a.histogram.size == b.histogram.size) { "histogrammen moeten even groot zijn" }

        var sum = 0f
        for (i in a.histogram.indices) sum += abs(a.histogram[i] - b.histogram[i])
        return sum / 2f
    }
}
