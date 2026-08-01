package nl.artifation.videoeditor.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RATE = 48_000

private fun sine(seconds: Double, amplitude: Float, hz: Double = 1_000.0): FloatArray {
    val count = (seconds * RATE).toInt()
    return FloatArray(count) { (sin(2 * PI * hz * it / RATE) * amplitude).toFloat() }
}

private fun silence(seconds: Double) = FloatArray((seconds * RATE).toInt())

class KWeightingTest {

    /**
     * De gepubliceerde BS.1770-coëfficiënten voor 48 kHz. Als de afleiding uit de
     * analoge prototypes deze reproduceert, klopt hij ook op andere samplerates.
     */
    @Test
    fun `shelf-coefficienten komen overeen met de spec op 48 kHz`() {
        val c = Loudness.shelfCoefficients(48_000)

        assertEquals(1.53512485958697, c.b0, 1e-10)
        assertEquals(-2.69169618940638, c.b1, 1e-10)
        assertEquals(1.19839281085285, c.b2, 1e-10)
        assertEquals(-1.69065929318241, c.a1, 1e-10)
        assertEquals(0.73248077421585, c.a2, 1e-10)
    }

    @Test
    fun `highpass-coefficienten komen overeen met de spec op 48 kHz`() {
        val c = Loudness.highPassCoefficients(48_000)

        assertEquals(1.0, c.b0, 1e-10)
        assertEquals(-2.0, c.b1, 1e-10)
        assertEquals(1.0, c.b2, 1e-10)
        assertEquals(-1.99004745483398, c.a1, 1e-8)
        assertEquals(0.99007225036621, c.a2, 1e-8)
    }

    @Test
    fun `de coefficienten verschillen per samplerate`() {
        assertTrue(
            abs(Loudness.shelfCoefficients(44_100).b0 - Loudness.shelfCoefficients(48_000).b0) > 1e-6,
            "44,1 kHz hoort andere coëfficiënten te geven dan 48 kHz",
        )
    }
}

class LoudnessMeasureTest {

    @Test
    fun `twee keer zo hard is ongeveer zes dB luider`() {
        val quiet = Loudness.measure(listOf(sine(3.0, 0.1f)), RATE).integratedLufs!!
        val loud = Loudness.measure(listOf(sine(3.0, 0.2f)), RATE).integratedLufs!!

        assertEquals(6.02f, loud - quiet, 0.05f)
    }

    @Test
    fun `een tweede identiek kanaal telt op tot drie dB`() {
        val channel = sine(3.0, 0.1f)
        val mono = Loudness.measure(listOf(channel), RATE).integratedLufs!!
        val stereo = Loudness.measure(listOf(channel, channel.copyOf()), RATE).integratedLufs!!

        assertEquals(3.01f, stereo - mono, 0.05f)
    }

    @Test
    fun `stilte levert geen meting op`() {
        val result = Loudness.measure(listOf(silence(3.0)), RATE)

        assertNull(result.integratedLufs)
        assertEquals(0, result.gatedBlocks)
    }

    @Test
    fun `materiaal korter dan een blok levert geen meting op`() {
        assertNull(Loudness.measure(listOf(sine(0.1, 0.5f)), RATE).integratedLufs)
    }

    @Test
    fun `leeg materiaal levert geen meting op`() {
        assertNull(Loudness.measure(emptyList(), RATE).integratedLufs)
        assertNull(Loudness.measure(listOf(FloatArray(0)), RATE).integratedLufs)
    }
}

class LoudnessGatingTest {

    /**
     * Dit is waarom gating bestaat: zonder de gate zou een lange stilte de meting
     * omlaag trekken en zou normalisatie het materiaal veel te hard maken.
     */
    @Test
    fun `lange stiltes drukken de meting niet omlaag`() {
        val spraak = sine(3.0, 0.2f)
        val metStilte = spraak + silence(6.0)

        val zonder = Loudness.measure(listOf(spraak), RATE).integratedLufs!!
        val met = Loudness.measure(listOf(metStilte), RATE).integratedLufs!!

        assertEquals(zonder, met, 0.3f, "stilte hoort weggegate te worden")
    }

    @Test
    fun `zachte passages onder de relatieve gate tellen niet mee`() {
        // 20 dB verschil is ruim meer dan de relatieve gate van -10 LU.
        val luid = sine(3.0, 0.5f)
        val zacht = sine(3.0, 0.05f)

        val alleenLuid = Loudness.measure(listOf(luid), RATE).integratedLufs!!
        val gemengd = Loudness.measure(listOf(luid + zacht), RATE).integratedLufs!!

        assertEquals(alleenLuid, gemengd, 0.5f)
    }
}

class LoudnessNormalizationTest {

    @Test
    fun `normaliseren naar het doel levert precies het doel op`() {
        val target = -14f
        val audio = sine(4.0, 0.05f)

        val factor = Loudness.measure(listOf(audio), RATE).gainFactorTo(target)
        val normalized = Loudness.applyGain(audio, factor)
        val measured = Loudness.measure(listOf(normalized), RATE).integratedLufs

        assertNotNull(measured)
        assertEquals(target, measured, 0.1f)
    }

    @Test
    fun `zonder meting wordt er niets versterkt`() {
        val result = Loudness.measure(listOf(silence(3.0)), RATE)

        assertEquals(0f, result.gainDbTo(-14f))
        assertEquals(1f, result.gainFactorTo(-14f), 1e-6f)
    }

    @Test
    fun `versterken klipt niet buiten het bereik`() {
        val amplified = Loudness.applyGain(sine(0.1, 0.9f), factor = 4f)
        assertTrue(amplified.all { it in -1f..1f }, "samples buiten [-1, 1]")
    }
}
