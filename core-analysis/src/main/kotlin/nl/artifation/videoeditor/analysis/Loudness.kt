package nl.artifation.videoeditor.analysis

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Loudnessmeting volgens ITU-R BS.1770-4 / EBU R128.
 *
 * Waarom niet gewoon RMS: een piek in de bas telt voor het oor veel minder zwaar dan
 * dezelfde piek rond 2 kHz, en een RMS-getal over het hele bestand wordt omlaag
 * getrokken door alle stiltes ertussen. De norm lost allebei op — een frequentieweging
 * (K-weighting) en twee poorten die stille stukken buiten de meting houden.
 *
 * [integratedLufs] is `null` als er niets boven de absolute poort van −70 LUFS uitkomt:
 * digitale stilte heeft geen loudness, en 0 of −∞ teruggeven zou verderop stilletjes
 * een onzinnige gain opleveren.
 */
@Serializable
public data class LoudnessResult(
    val integratedLufs: Float?,
    /** Hoogste sample, niet de true peak — daarvoor is oversampling nodig. */
    val samplePeakDbfs: Float,
) {
    /**
     * Hoeveel dB erbij moet om op [targetLufs] uit te komen.
     *
     * Zonder meting geen correctie: liever ongewijzigd doorlaten dan gokken.
     */
    public fun gainDbForTarget(targetLufs: Float): Float =
        if (integratedLufs == null) 0f else targetLufs - integratedLufs

    /**
     * Ruimte tot 0 dBFS na [gainDbForTarget]. Negatief betekent clipping — dan is
     * een limiter of een lager doelniveau nodig.
     */
    public fun headroomDbForTarget(targetLufs: Float): Float =
        -(samplePeakDbfs + gainDbForTarget(targetLufs))
}

public object LoudnessMeter {

    /** Vensterlengte uit de norm. */
    private const val BLOCK_MS = 400

    /** 75 % overlap: elk blok schuift een kwart blok op. */
    private const val OVERLAP_DIVISOR = 4

    /** Blokken hieronder tellen nooit mee, hoe stil de rest ook is. */
    private const val ABSOLUTE_GATE_LUFS = -70.0

    /** De relatieve poort ligt 10 LU onder het ongepoorte gemiddelde. */
    private const val RELATIVE_GATE_LU = -10.0

    /**
     * Kalibratie-offset uit de norm: hiermee leest een sinus van 997 Hz op −23 dBFS
     * exact −23 LUFS, ondanks de versterking van het K-filter op die frequentie.
     */
    private const val OFFSET_DB = -0.691

    /**
     * @param channels één array per kanaal, samples in [-1, 1], allemaal even lang.
     *   Mono en stereo worden ondersteund; dat dekt telefoonopnames. Surround vraagt
     *   om de kanaalgewichten uit de norm en is bewust niet geraden.
     */
    public fun measure(channels: List<FloatArray>, sampleRate: Int): LoudnessResult {
        require(sampleRate > 0) { "sampleRate moet positief zijn" }
        require(channels.isNotEmpty()) { "minstens één kanaal nodig" }
        require(channels.size <= 2) { "alleen mono en stereo worden ondersteund, kreeg ${channels.size} kanalen" }
        require(channels.all { it.size == channels.first().size }) { "kanalen moeten even lang zijn" }

        val peakDbfs = toDbfs(channels.maxOf { channel -> channel.maxOfOrNull { abs(it) } ?: 0f })
        val sampleCount = channels.first().size

        val blockSize = sampleRate * BLOCK_MS / 1000
        if (sampleCount < blockSize) return LoudnessResult(null, peakDbfs)

        val weighted = channels.map { kWeight(it, sampleRate) }
        val step = blockSize / OVERLAP_DIVISOR

        // Per blok het gewogen gemiddelde kwadraat; de loudness volgt daaruit.
        val blockPower = buildList {
            var start = 0
            while (start + blockSize <= sampleCount) {
                add(weighted.sumOf { meanSquare(it, start, blockSize) })
                start += step
            }
        }

        val aboveAbsolute = blockPower.filter { loudnessOf(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return LoudnessResult(null, peakDbfs)

        val relativeGate = loudnessOf(aboveAbsolute.average()) + RELATIVE_GATE_LU
        val gated = aboveAbsolute.filter { loudnessOf(it) > relativeGate }
        if (gated.isEmpty()) return LoudnessResult(null, peakDbfs)

        return LoudnessResult(loudnessOf(gated.average()).toFloat(), peakDbfs)
    }

    public fun measure(mono: FloatArray, sampleRate: Int): LoudnessResult =
        measure(listOf(mono), sampleRate)

    private fun loudnessOf(power: Double): Double =
        if (power <= 0.0) Double.NEGATIVE_INFINITY else OFFSET_DB + 10.0 * log10(power)

    private fun meanSquare(samples: DoubleArray, offset: Int, length: Int): Double {
        var sum = 0.0
        for (i in offset until offset + length) sum += samples[i] * samples[i]
        return sum / length
    }

    private fun toDbfs(amplitude: Float): Float =
        if (amplitude <= 0f) -144f else (20.0 * log10(amplitude.toDouble())).toFloat()

    /** Het tweetrapsfilter uit de norm: eerst de hoofdafscherming, dan de RLB-hoogdoorlaat. */
    internal fun kWeight(samples: FloatArray, sampleRate: Int): DoubleArray {
        val input = DoubleArray(samples.size) { samples[it].toDouble() }
        return rlbHighPass(sampleRate).process(shelvingFilter(sampleRate).process(input))
    }

    /**
     * Trap 1: high-shelf die het effect van het hoofd nabootst.
     *
     * De norm geeft alleen coëfficiënten voor 48 kHz. Die hier hardcoderen zou op
     * 44,1 kHz-materiaal — waar een telefoon zomaar mee aan kan komen — een stil
     * verkeerde meting geven. Daarom uit het analoge prototype gerekend.
     */
    internal fun shelvingFilter(sampleRate: Int): Biquad {
        val f0 = 1681.974450955533
        val gainDb = 3.999843853973347
        val q = 0.7071752369554196

        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val denom = 1.0 + k / q + k * k

        return Biquad(
            b0 = (vh + vb * k / q + k * k) / denom,
            b1 = 2.0 * (k * k - vh) / denom,
            b2 = (vh - vb * k / q + k * k) / denom,
            a1 = 2.0 * (k * k - 1.0) / denom,
            a2 = (1.0 - k / q + k * k) / denom,
        )
    }

    /** Trap 2: RLB-hoogdoorlaat, haalt de laagfrequente energie eruit die je niet hoort. */
    internal fun rlbHighPass(sampleRate: Int): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773

        val k = tan(PI * f0 / sampleRate)
        val denom = 1.0 + k / q + k * k

        return Biquad(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k * k - 1.0) / denom,
            a2 = (1.0 - k / q + k * k) / denom,
        )
    }
}

/** Biquad in direct form I, in `Double` omdat de RLB-polen dicht bij de eenheidscirkel liggen. */
internal data class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    fun process(input: DoubleArray): DoubleArray {
        val output = DoubleArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        for (i in input.indices) {
            val x = input[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[i] = y
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
        }
        return output
    }
}
