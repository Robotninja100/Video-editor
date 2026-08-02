package nl.artifation.videoeditor.analysis

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Loudness-meting volgens ITU-R BS.1770 / EBU R128.
 *
 * Dit is het verschil tussen "amateur" en "af" dat je eerder hoort dan ziet:
 * clips uit verschillende opnames verschillen makkelijk 10 dB in luidheid, en
 * zonder normalisatie hoor je elke las.
 *
 * De meting is geen simpele RMS. Er zitten twee dingen in die ertoe doen:
 * **K-weging** (een filter dat nabootst hoe het oor frequenties weegt) en
 * **gating** (stiltes tellen niet mee, anders drukt een lange pauze de meting
 * omlaag en wordt het materiaal te hard genormaliseerd).
 */
public data class LoudnessResult(
    /** Integrated loudness in LUFS. `null` als er niets boven de gate uitkwam. */
    val integratedLufs: Float?,
    /** Aantal blokken dat de gating overleefde. */
    val gatedBlocks: Int,
) {
    /** Versterking in dB om [targetLufs] te halen; 0 als er niets te meten viel. */
    public fun gainDbTo(targetLufs: Float): Float =
        integratedLufs?.let { targetLufs - it } ?: 0f

    /** Lineaire factor om [targetLufs] te halen. */
    public fun gainFactorTo(targetLufs: Float): Float =
        BASE_TEN.pow(gainDbTo(targetLufs) / DB_PER_AMPLITUDE_DECADE)

    private companion object {
        const val BASE_TEN: Float = 10f

        /** Amplitude in dB is 20·log10; vermogen in dB is 10·log10. */
        const val DB_PER_AMPLITUDE_DECADE: Float = 20f
    }
}

public object Loudness {

    /** Offset uit BS.1770 die de filtersom naar de LUFS-schaal brengt. */
    private const val OFFSET_DB = -0.691f

    /** Blokken hieronder tellen nooit mee, ongeacht de rest. */
    private const val ABSOLUTE_GATE_LUFS = -70f

    /** Blokken meer dan dit onder het ongegate gemiddelde vallen af. */
    private const val RELATIVE_GATE_LU = -10f

    private const val BLOCK_MS = 400
    private const val OVERLAP = 0.75
    private const val MS_PER_SECOND = 1000

    /** Vermogen in dB is 10·log10, niet 20·log10 — hier wordt kwadraat gemeten. */
    private const val DB_PER_POWER_DECADE = 10f

    /**
     * Meet integrated loudness van één of meer kanalen.
     *
     * @param channels per kanaal de samples in [-1, 1]; alle even lang
     */
    public fun measure(channels: List<FloatArray>, sampleRate: Int): LoudnessResult {
        require(sampleRate > 0) { "sampleRate moet positief zijn" }
        if (channels.isNotEmpty() && channels.first().isNotEmpty()) {
            require(channels.all { it.size == channels.first().size }) {
                "alle kanalen moeten even lang zijn"
            }
        }

        val gated = gate(blockPowers(channels, sampleRate))
        return if (gated.isEmpty()) {
            LoudnessResult(null, 0)
        } else {
            LoudnessResult(integratedLufs = toLufs(gated.average()), gatedBlocks = gated.size)
        }
    }

    /** Gemiddelde kwadraat per blok, gesommeerd over kanalen (G = 1 voor L/R). */
    private fun blockPowers(channels: List<FloatArray>, sampleRate: Int): List<Double> {
        val blockSize = (sampleRate * BLOCK_MS / MS_PER_SECOND).coerceAtLeast(1)
        if (channels.isEmpty() || channels.first().size < blockSize) return emptyList()

        val weighted = channels.map { kWeight(it, sampleRate) }
        val hop = (blockSize * (1.0 - OVERLAP)).toInt().coerceAtLeast(1)

        val blockPower = mutableListOf<Double>()
        var offset = 0
        while (offset + blockSize <= weighted.first().size) {
            var power = 0.0
            for (channel in weighted) {
                var sum = 0.0
                for (i in offset until offset + blockSize) {
                    sum += channel[i].toDouble() * channel[i]
                }
                power += sum / blockSize
            }
            blockPower.add(power)
            offset += hop
        }
        return blockPower
    }

    /**
     * De gating in twee trappen: eerst wat er absoluut te stil is, daarna wat
     * er te stil is ten opzichte van de rest. Zonder die tweede trap drukt een
     * lange pauze de meting omlaag.
     */
    private fun gate(blockPower: List<Double>): List<Double> {
        val aboveAbsolute = blockPower.filter { toLufs(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return emptyList()

        val relativeThreshold = toLufs(aboveAbsolute.average()) + RELATIVE_GATE_LU
        return aboveAbsolute.filter { toLufs(it) > relativeThreshold }
    }

    /** Past een versterking toe; waarden buiten [-1, 1] worden geclipt. */
    public fun applyGain(samples: FloatArray, factor: Float): FloatArray =
        FloatArray(samples.size) { (samples[it] * factor).coerceIn(-1f, 1f) }

    private fun toLufs(meanSquare: Double): Float =
        if (meanSquare <= 0.0) {
            Float.NEGATIVE_INFINITY
        } else {
            OFFSET_DB + DB_PER_POWER_DECADE * log10(meanSquare).toFloat()
        }

    /**
     * K-weging: een high-shelf die de hoge tonen optilt, gevolgd door een
     * high-pass die de laagste tonen wegneemt. Coëfficiënten uit de analoge
     * prototypes van BS.1770 via bilineaire transformatie, zodat elke
     * samplerate klopt en niet alleen 48 kHz.
     */
    internal fun kWeight(samples: FloatArray, sampleRate: Int): FloatArray =
        biquad(biquad(samples, shelfCoefficients(sampleRate)), highPassCoefficients(sampleRate))

    internal data class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    // De getallen hieronder komen letterlijk uit BS.1770 — ze zijn niet afgerond
    // en niet zelf gekozen. Elke wijziging is een andere meting.
    private const val SHELF_FREQUENCY_HZ = 1681.974450955533
    private const val SHELF_GAIN_DB = 3.999843853973347
    private const val SHELF_Q = 0.7071752369554196
    private const val SHELF_VB_EXPONENT = 0.4996667741545416
    private const val HIGH_PASS_FREQUENCY_HZ = 38.13547087602444
    private const val HIGH_PASS_Q = 0.5003270373238773

    private const val BASE_TEN = 10.0
    private const val DB_PER_AMPLITUDE_DECADE = 20.0

    internal fun shelfCoefficients(sampleRate: Int): Biquad {
        val f0 = SHELF_FREQUENCY_HZ
        val q = SHELF_Q

        val k = tan(PI * f0 / sampleRate)
        val vh = BASE_TEN.pow(SHELF_GAIN_DB / DB_PER_AMPLITUDE_DECADE)
        val vb = vh.pow(SHELF_VB_EXPONENT)
        val a0 = 1.0 + k / q + k * k

        return Biquad(
            b0 = (vh + vb * k / q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    internal fun highPassCoefficients(sampleRate: Int): Biquad {
        val f0 = HIGH_PASS_FREQUENCY_HZ
        val q = HIGH_PASS_Q

        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k

        return Biquad(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    private fun biquad(samples: FloatArray, c: Biquad): FloatArray {
        val out = FloatArray(samples.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            out[i] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }
}
