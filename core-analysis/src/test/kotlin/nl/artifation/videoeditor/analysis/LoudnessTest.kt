package nl.artifation.videoeditor.analysis

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RATE = 48_000

/**
 * Sinus op een niveau in dBFS volgens de conventie van de norm: een sinus met
 * volle uitslag is 0 dBFS, dus de amplitude is √2 keer de RMS-waarde.
 */
private fun sine(
    dbfs: Double,
    seconds: Double,
    sampleRate: Int = RATE,
    frequencyHz: Double = 997.0,
): FloatArray {
    val amplitude = sqrt(2.0) * 10.0.pow(dbfs / 20.0)
    val count = (seconds * sampleRate).toInt()
    return FloatArray(count) {
        (amplitude * sin(2.0 * PI * frequencyHz * it / sampleRate)).toFloat()
    }
}

private fun concat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

/** De coëfficiënten uit tabel 1 en 2 van ITU-R BS.1770-4, voor 48 kHz. */
class KWeightingCoefficientTest {

    private val tolerance = 1e-5

    @Test
    fun `de shelving-trap komt overeen met de tabel uit de norm`() {
        val filter = LoudnessMeter.shelvingFilter(48_000)

        assertEquals(1.53512485958697, filter.b0, tolerance)
        assertEquals(-2.69169618940638, filter.b1, tolerance)
        assertEquals(1.19839281085285, filter.b2, tolerance)
        assertEquals(-1.69065929318241, filter.a1, tolerance)
        assertEquals(0.73248077421585, filter.a2, tolerance)
    }

    @Test
    fun `de RLB-trap komt overeen met de tabel uit de norm`() {
        val filter = LoudnessMeter.rlbHighPass(48_000)

        assertEquals(1.0, filter.b0, tolerance)
        assertEquals(-2.0, filter.b1, tolerance)
        assertEquals(1.0, filter.b2, tolerance)
        assertEquals(-1.99004745483398, filter.a1, tolerance)
        assertEquals(0.99007225036621, filter.a2, tolerance)
    }

    @Test
    fun `andere samplerates leveren andere coefficienten op`() {
        val at48k = LoudnessMeter.shelvingFilter(48_000)
        val at44k = LoudnessMeter.shelvingFilter(44_100)

        assertTrue(at48k != at44k, "de tabel uit de norm geldt alleen voor 48 kHz")
    }
}

/** De referentiesignalen uit EBU Tech 3341. */
class IntegratedLoudnessTest {

    private val tolerance = 0.1f

    @Test
    fun `een sinus van min 23 dBFS leest min 23 LUFS`() {
        val result = LoudnessMeter.measure(sine(-23.0, seconds = 20.0), RATE)

        assertEquals(-23.0f, assertNotNull(result.integratedLufs), tolerance)
    }

    @Test
    fun `een sinus van min 33 dBFS leest min 33 LUFS`() {
        val result = LoudnessMeter.measure(sine(-33.0, seconds = 20.0), RATE)

        assertEquals(-33.0f, assertNotNull(result.integratedLufs), tolerance)
    }

    @Test
    fun `de relatieve poort houdt zachte randen buiten de meting`() {
        // EBU Tech 3341, testcase 3: de stukken van −36 dBFS liggen meer dan 10 LU
        // onder het gemiddelde en tellen daarom niet mee.
        val signal = concat(
            sine(-36.0, seconds = 10.0),
            sine(-23.0, seconds = 60.0),
            sine(-36.0, seconds = 10.0),
        )

        val result = LoudnessMeter.measure(signal, RATE)

        assertEquals(-23.0f, assertNotNull(result.integratedLufs), tolerance)
    }

    @Test
    fun `de absolute poort houdt bijna-stilte buiten de meting`() {
        // EBU Tech 3341, testcase 4: −72 dBFS ligt onder de absolute poort van −70 LUFS.
        val signal = concat(
            sine(-72.0, seconds = 10.0),
            sine(-36.0, seconds = 60.0),
            sine(-72.0, seconds = 10.0),
        )

        val result = LoudnessMeter.measure(signal, RATE)

        assertEquals(-36.0f, assertNotNull(result.integratedLufs), tolerance)
    }

    @Test
    fun `de meting is onafhankelijk van de samplerate`() {
        val at48k = LoudnessMeter.measure(sine(-23.0, seconds = 20.0, sampleRate = 48_000), 48_000)
        val at44k = LoudnessMeter.measure(sine(-23.0, seconds = 20.0, sampleRate = 44_100), 44_100)

        assertEquals(
            assertNotNull(at48k.integratedLufs),
            assertNotNull(at44k.integratedLufs),
            tolerance,
            "een telefoon kan zomaar met 44,1 kHz aankomen",
        )
    }

    @Test
    fun `hetzelfde signaal op twee kanalen is 3 LU luider`() {
        val mono = sine(-23.0, seconds = 20.0)

        val single = assertNotNull(LoudnessMeter.measure(mono, RATE).integratedLufs)
        val double = assertNotNull(LoudnessMeter.measure(listOf(mono, mono), RATE).integratedLufs)

        assertEquals(3.01f, double - single, 0.05f, "de norm telt de kanaalvermogens op")
    }
}

class LoudnessEdgeCaseTest {

    @Test
    fun `digitale stilte heeft geen loudness`() {
        val result = LoudnessMeter.measure(FloatArray(RATE * 5), RATE)

        assertNull(result.integratedLufs, "0 of −∞ zou verderop stilletjes een onzinnige gain geven")
        assertEquals(-144f, result.samplePeakDbfs)
    }

    @Test
    fun `materiaal korter dan een blok levert geen meting op`() {
        val result = LoudnessMeter.measure(sine(-23.0, seconds = 0.3), RATE)

        assertNull(result.integratedLufs)
    }

    @Test
    fun `de samplepiek wordt in dBFS gerapporteerd`() {
        val result = LoudnessMeter.measure(sine(-13.0, seconds = 1.0), RATE)

        // Twee conventies die makkelijk door elkaar lopen: het signaalniveau van de
        // norm gaat over de RMS van een sinus, de samplepiek over de amplitude.
        // Die schelen √2, oftewel 3,01 dB — een sinus van "−3 dBFS" clipt dus net.
        assertEquals(-13.0f + 3.01f, result.samplePeakDbfs, 0.1f)
    }

    @Test
    fun `kanalen van ongelijke lengte worden geweigerd`() {
        assertFailsWith<IllegalArgumentException> {
            LoudnessMeter.measure(listOf(FloatArray(100), FloatArray(200)), RATE)
        }
    }

    @Test
    fun `surround wordt geweigerd in plaats van geraden`() {
        assertFailsWith<IllegalArgumentException> {
            LoudnessMeter.measure(List(6) { FloatArray(RATE) }, RATE)
        }
    }
}

class LoudnessGainTest {

    @Test
    fun `de gain brengt de meting op het doelniveau`() {
        val result = LoudnessResult(integratedLufs = -18.5f, samplePeakDbfs = -1f)

        assertEquals(4.5f, result.gainDbForTarget(-14f), 1e-4f)
    }

    @Test
    fun `zonder meting wordt er niets gecorrigeerd`() {
        val result = LoudnessResult(integratedLufs = null, samplePeakDbfs = -144f)

        assertEquals(0f, result.gainDbForTarget(-14f))
    }

    @Test
    fun `te weinig ruimte tot 0 dBFS wordt zichtbaar`() {
        val result = LoudnessResult(integratedLufs = -18f, samplePeakDbfs = -2f)

        // +4 dB gain op een piek van −2 dBFS zou clippen.
        assertEquals(-2f, result.headroomDbForTarget(-14f), 1e-4f)
        assertTrue(result.headroomDbForTarget(-14f) < 0f)
    }
}
