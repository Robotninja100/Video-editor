package nl.artifation.videoeditor.analysis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000

/** Bouwt een testsignaal uit blokken van (duur in seconden, amplitude). */
private fun signal(vararg blocks: Pair<Double, Float>): FloatArray {
    val out = mutableListOf<Float>()
    var phase = 0.0
    for ((seconds, amplitude) in blocks) {
        repeat((seconds * SAMPLE_RATE).toInt()) {
            out.add((sin(phase) * amplitude).toFloat())
            phase += 2 * PI * 440.0 / SAMPLE_RATE
        }
    }
    return out.toFloatArray()
}

private const val TOLERANCE_US = 25_000L // één 20ms-venster, met marge

private fun assertNear(expectedUs: Long, actualUs: Long, label: String) {
    assertTrue(
        kotlin.math.abs(expectedUs - actualUs) <= TOLERANCE_US,
        "$label: verwacht ~${expectedUs}us, was ${actualUs}us",
    )
}

class SilenceDetectorTest {

    @Test
    fun `vindt een stilte tussen twee luide stukken`() {
        val audio = signal(1.0 to 0.5f, 1.0 to 0f, 1.0 to 0.5f)

        val silences = SilenceDetector.detect(audio, SAMPLE_RATE)

        assertEquals(1, silences.size, "gevonden: $silences")
        // Ruwe stilte is 1.0s..2.0s; padding van 120ms kort beide kanten in.
        assertNear(1_120_000L, silences[0].startUs, "start")
        assertNear(1_880_000L, silences[0].endUs, "eind")
    }

    @Test
    fun `padding kort de stilte in zodat spraak niet wordt afgekapt`() {
        val audio = signal(1.0 to 0.5f, 1.0 to 0f, 1.0 to 0.5f)

        val zonderPadding = SilenceDetector.detect(
            audio, SAMPLE_RATE, SilenceConfig(paddingMs = 0),
        ).single()
        val metPadding = SilenceDetector.detect(
            audio, SAMPLE_RATE, SilenceConfig(paddingMs = 120),
        ).single()

        assertTrue(metPadding.startUs > zonderPadding.startUs)
        assertTrue(metPadding.endUs < zonderPadding.endUs)
        assertEquals(240_000L, zonderPadding.durationUs - metPadding.durationUs)
    }

    @Test
    fun `korte stiltes worden genegeerd`() {
        // 100ms stilte, ruim onder minSilenceMs van 300.
        val audio = signal(1.0 to 0.5f, 0.1 to 0f, 1.0 to 0.5f)

        assertEquals(emptyList(), SilenceDetector.detect(audio, SAMPLE_RATE))
    }

    @Test
    fun `stilte aan het einde wordt afgesloten`() {
        val audio = signal(1.0 to 0.5f, 1.0 to 0f)

        val silences = SilenceDetector.detect(audio, SAMPLE_RATE)
        assertEquals(1, silences.size)
        assertNear(1_880_000L, silences.single().endUs, "eind van het signaal")
    }

    @Test
    fun `hysterese voorkomt flapperen bij een niveau tussen de drempels`() {
        // -42 dBFS ligt tussen enterDb (-45) en exitDb (-40): eenmaal in stilte
        // blijft het stil, en zonder eerst onder -45 te zakken begint het niet.
        val betweenThresholds = 0.0112f // ~ -42 dBFS voor een sinus
        val audio = signal(1.0 to 0.5f, 0.5 to betweenThresholds, 0.5 to 0f, 0.5 to betweenThresholds)

        val silences = SilenceDetector.detect(audio, SAMPLE_RATE)

        assertEquals(1, silences.size, "één doorlopende stilte, geen fragmenten: $silences")
        assertTrue(silences.single().durationUs > 400_000L, "stilte loopt door: ${silences.single()}")
    }

    @Test
    fun `volledig stil materiaal levert één stilte op`() {
        val silences = SilenceDetector.detect(signal(2.0 to 0f), SAMPLE_RATE)
        assertEquals(1, silences.size)
        assertTrue(silences.single().rmsDb <= -99f)
    }

    @Test
    fun `leeg materiaal levert niets op`() {
        assertEquals(emptyList(), SilenceDetector.detect(FloatArray(0), SAMPLE_RATE))
    }
}

class KeepIntervalsTest {

    @Test
    fun `keepIntervals is het complement van de stiltes`() {
        val silences = listOf(SilenceInterval(1_000L, 2_000L, -60f))

        val keeps = SilenceDetector.keepIntervals(silences, totalDurationUs = 3_000L)

        assertEquals(listOf(0L until 1_000L, 2_000L until 3_000L), keeps)
    }

    @Test
    fun `stilte aan het begin levert geen leeg interval op`() {
        val silences = listOf(SilenceInterval(0L, 1_000L, -60f))

        val keeps = SilenceDetector.keepIntervals(silences, totalDurationUs = 2_000L)

        assertEquals(listOf(1_000L until 2_000L), keeps)
    }

    @Test
    fun `zonder stiltes blijft alles behouden`() {
        assertEquals(
            listOf(0L until 5_000L),
            SilenceDetector.keepIntervals(emptyList(), totalDurationUs = 5_000L),
        )
    }
}

class DownmixTest {

    @Test
    fun `stereo wordt gemiddeld naar mono`() {
        val stereo = floatArrayOf(1f, 0f, 0.5f, 0.5f)
        assertEquals(listOf(0.5f, 0.5f), downmixToMono(stereo, channels = 2).toList())
    }

    @Test
    fun `mono blijft ongewijzigd`() {
        val mono = floatArrayOf(0.1f, 0.2f)
        assertEquals(mono, downmixToMono(mono, channels = 1))
    }
}
