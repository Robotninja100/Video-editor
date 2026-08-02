package nl.artifation.videoeditor.analysis

import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.model.Us
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shot-detectie op basis van histogramverschil.
 *
 * Geen AI nodig: een harde cut is een abrupte sprong in de luma-verdeling.
 * De uitkomst stuurt de reframe-smoothing aan — op een shot-grens mág de crop
 * springen, daartussen nooit.
 */
@Serializable
public data class SceneCut(
    val atUs: Us,
    /** Hoe sterk het histogram verschilde, 0..1. Handig om drempels te tunen. */
    val score: Float,
)

/** Genormaliseerd luma-histogram van één frame. */
public data class FrameHistogram(
    val atUs: Us,
    val bins: FloatArray,
) {
    init {
        require(bins.isNotEmpty()) { "histogram mag niet leeg zijn" }
    }

    // Handmatig, want FloatArray gebruikt referentiegelijkheid in een data class.
    override fun equals(other: Any?): Boolean =
        other is FrameHistogram && atUs == other.atUs && bins.contentEquals(other.bins)

    override fun hashCode(): Int = 31 * atUs.hashCode() + bins.contentHashCode()
}

public data class SceneConfig(
    /** Vaste ondergrens: hieronder is het nooit een cut. */
    val minScore: Float = 0.35f,
    /**
     * Adaptieve drempel: een cut moet dit veelvoud van het *mediane* verschil
     * zijn. Vangt materiaal met veel of juist weinig beweging op.
     */
    val adaptiveFactor: Float = 3f,
    /** Kortere shots dan dit worden niet als aparte shot geteld. */
    val minShotDurationUs: Us = 400_000L,
) {
    init {
        require(minScore >= 0f) { "minScore moet >= 0 zijn" }
        require(adaptiveFactor >= 1f) { "adaptiveFactor moet >= 1 zijn" }
        require(minShotDurationUs >= 0L) { "minShotDurationUs moet >= 0 zijn" }
    }
}

public object SceneDetector {

    public fun detect(
        histograms: List<FrameHistogram>,
        config: SceneConfig = SceneConfig(),
    ): List<SceneCut> {
        if (histograms.size < 2) return emptyList()

        val sorted = histograms.sortedBy { it.atUs }
        val scores = FloatArray(sorted.size - 1) { index ->
            distance(sorted[index].bins, sorted[index + 1].bins)
        }

        // Mediaan en niet gemiddelde: cuts zijn zelf grote uitschieters, dus een
        // gemiddelde wordt door de cuts omhooggetrokken en verbergt ze daarmee.
        // De mediaan blijft op het ruisniveau liggen zolang cuts een minderheid zijn.
        //
        // Maar zodra cuts géén minderheid zijn — een snelle montage, of frames die
        // ver uit elkaar bemonsterd zijn — tilt de mediaan de drempel boven de
        // hoogste haalbare afstand uit. De Hellinger-afstand komt nooit boven 1,
        // dus dan vindt de detectie er nul, hoe overduidelijk de cuts ook zijn.
        // Daarom kan de adaptieve drempel nooit boven de hoogste gemeten afstand
        // liggen; de vaste ondergrens beschermt materiaal zonder cuts.
        val adaptive = (median(scores) * config.adaptiveFactor).coerceAtMost(scores.max())
        val threshold = maxOf(config.minScore, adaptive)

        val cuts = mutableListOf<SceneCut>()
        var lastCutUs = sorted.first().atUs

        scores.forEachIndexed { index, score ->
            val atUs = sorted[index + 1].atUs
            if (score >= threshold && atUs - lastCutUs >= config.minShotDurationUs) {
                cuts.add(SceneCut(atUs, score))
                lastCutUs = atUs
            }
        }
        return cuts
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    /**
     * Hellinger-afstand tussen twee genormaliseerde histogrammen: 0 = identiek,
     * 1 = geen overlap. Minder gevoelig voor belichtingsruis dan chi-kwadraat.
     */
    internal fun distance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "histogrammen moeten evenveel bins hebben" }
        val normA = normalize(a)
        val normB = normalize(b)
        var bhattacharyya = 0.0
        for (i in normA.indices) {
            bhattacharyya += sqrt(normA[i].toDouble() * normB[i])
        }
        return sqrt(maxOf(0.0, 1.0 - bhattacharyya)).toFloat()
    }

    private fun normalize(bins: FloatArray): FloatArray {
        var sum = 0f
        for (bin in bins) sum += abs(bin)
        if (sum <= 0f) return FloatArray(bins.size) { 1f / bins.size }
        return FloatArray(bins.size) { abs(bins[it]) / sum }
    }
}
