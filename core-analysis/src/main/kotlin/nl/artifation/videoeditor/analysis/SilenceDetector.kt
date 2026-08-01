package nl.artifation.videoeditor.analysis

import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stiltedetectie voor auto-knippen.
 *
 * Bewust pure DSP en geen AI: RMS per venster met een hysterese-drempel is
 * sneller, deterministisch en makkelijk te debuggen. Deze klasse is pure JVM en
 * daarom zonder toestel te testen.
 */
@Serializable
public data class SilenceInterval(
    val startUs: Us,
    val endUs: Us,
    val rmsDb: Float,
) {
    val durationUs: Us get() = endUs - startUs
}

public data class SilenceConfig(
    val windowMs: Int = 20,
    /** Stilte begint zodra het niveau hieronder zakt. */
    val enterDb: Float = -45f,
    /** Stilte eindigt pas zodra het niveau hierboven komt. Hoger dan [enterDb] — dat is de hysterese. */
    val exitDb: Float = -40f,
    /** Kortere stiltes worden genegeerd; anders knip je midden in adempauzes. */
    val minSilenceMs: Int = 300,
    /** Stiltes worden aan beide kanten ingekort, zodat spraak niet wordt afgekapt. */
    val paddingMs: Int = 120,
) {
    init {
        require(windowMs > 0) { "windowMs moet positief zijn" }
        require(exitDb >= enterDb) { "exitDb ($exitDb) moet >= enterDb ($enterDb) zijn voor hysterese" }
        require(minSilenceMs >= 0) { "minSilenceMs moet >= 0 zijn" }
        require(paddingMs >= 0) { "paddingMs moet >= 0 zijn" }
    }
}

public object SilenceDetector {

    /** Ondergrens voor de dB-schaal, zodat digitale stilte geen -Infinity oplevert. */
    private const val FLOOR_DB = -100f

    /**
     * @param mono mono samples in [-1, 1]
     * @param sampleRate samples per seconde
     */
    public fun detect(
        mono: FloatArray,
        sampleRate: Int,
        config: SilenceConfig = SilenceConfig(),
    ): List<SilenceInterval> {
        require(sampleRate > 0) { "sampleRate moet positief zijn" }
        if (mono.isEmpty()) return emptyList()

        val windowSize = max(1, sampleRate * config.windowMs / 1000)
        val levels = windowRms(mono, windowSize)
        val runs = findSilentRuns(levels, config)

        val windowUs = windowSize.toLong() * US_PER_SECOND / sampleRate
        val minSilenceUs = config.minSilenceMs.toLong() * 1_000L
        val paddingUs = config.paddingMs.toLong() * 1_000L

        return runs.mapNotNull { run ->
            val rawStartUs = run.startWindow * windowUs
            val rawEndUs = run.endWindow * windowUs
            if (rawEndUs - rawStartUs < minSilenceUs) return@mapNotNull null

            val startUs = rawStartUs + paddingUs
            val endUs = rawEndUs - paddingUs
            if (endUs <= startUs) return@mapNotNull null

            SilenceInterval(startUs = startUs, endUs = endUs, rmsDb = toDb(run.meanRms))
        }
    }

    /**
     * De stukken die je wilt hóuden: het complement van [silences] binnen
     * `0..totalDurationUs`. Dit is wat je direct naar clips omzet.
     */
    public fun keepIntervals(
        silences: List<SilenceInterval>,
        totalDurationUs: Us,
    ): List<LongRange> {
        val keeps = mutableListOf<LongRange>()
        var cursor = 0L
        for (silence in silences.sortedBy { it.startUs }) {
            val start = silence.startUs.coerceIn(0L, totalDurationUs)
            val end = silence.endUs.coerceIn(0L, totalDurationUs)
            if (start > cursor) keeps.add(cursor until start)
            cursor = max(cursor, end)
        }
        if (cursor < totalDurationUs) keeps.add(cursor until totalDurationUs)
        return keeps
    }

    private fun windowRms(mono: FloatArray, windowSize: Int): FloatArray {
        val count = mono.size / windowSize
        return FloatArray(count) { window ->
            var sum = 0.0
            val offset = window * windowSize
            for (i in offset until offset + windowSize) {
                sum += mono[i].toDouble() * mono[i]
            }
            sqrt(sum / windowSize).toFloat()
        }
    }

    private data class Run(val startWindow: Long, val endWindow: Long, val meanRms: Float)

    /** Toestandsmachine met hysterese: het niveau moet écht terugkomen voordat stilte eindigt. */
    private fun findSilentRuns(levels: FloatArray, config: SilenceConfig): List<Run> {
        val runs = mutableListOf<Run>()
        var inSilence = false
        var startWindow = 0
        var sum = 0.0

        levels.forEachIndexed { index, rms ->
            val db = toDb(rms)
            if (!inSilence && db < config.enterDb) {
                inSilence = true
                startWindow = index
                sum = 0.0
            }
            if (inSilence) {
                if (db > config.exitDb) {
                    val length = index - startWindow
                    runs.add(Run(startWindow.toLong(), index.toLong(), meanOf(sum, length)))
                    inSilence = false
                } else {
                    sum += rms.toDouble()
                }
            }
        }

        if (inSilence) {
            val length = levels.size - startWindow
            runs.add(Run(startWindow.toLong(), levels.size.toLong(), meanOf(sum, length)))
        }
        return runs
    }

    private fun meanOf(sum: Double, count: Int): Float =
        if (count <= 0) 0f else (sum / count).toFloat()

    private fun toDb(rms: Float): Float =
        if (rms <= 0f) FLOOR_DB else max(FLOOR_DB, 20f * log10(rms))
}

/** Mixt interleaved samples naar mono. */
public fun downmixToMono(interleaved: FloatArray, channels: Int): FloatArray {
    require(channels > 0) { "channels moet positief zijn" }
    if (channels == 1) return interleaved
    val frames = interleaved.size / channels
    return FloatArray(frames) { frame ->
        var sum = 0f
        for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
        sum / channels
    }
}
