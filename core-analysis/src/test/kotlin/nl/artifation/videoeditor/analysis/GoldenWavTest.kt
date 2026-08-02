package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.US_PER_SECOND
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RATE = 16_000

/**
 * De golden WAV uit `docs/BOUWPLAN.md` §Testen: een bestand met exact bekende
 * stilte-intervallen, dat de hele keten aflegt — schrijven, weer inlezen en
 * detecteren — in plaats van alleen een array in het geheugen.
 *
 * Dat vangt een klasse fouten die een geheugentest mist: 16-bits kwantisatie,
 * interleaving en samplerate die onderweg zoekraken.
 */
class GoldenWavTest {

    private val directory: File = Files.createTempDirectory("golden-wav").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** Toon van 1 s, stilte van 1 s, toon van 1 s. */
    private fun goldenSignal(): FloatArray {
        val second = RATE
        return FloatArray(3 * second) { index ->
            if (index in second until 2 * second) {
                0f
            } else {
                0.5f * sin(2.0 * PI * 440.0 * index / RATE).toFloat()
            }
        }
    }

    @Test
    fun `de stilte in het bestand wordt op de juiste plek gevonden`() {
        val file = File(directory, "golden.wav")
        WavFixtures.write(file, goldenSignal(), RATE)

        val pcm = WavFixtures.read(file)
        assertEquals(RATE, pcm.sampleRate)
        assertEquals(1, pcm.channels)

        val silences = SilenceDetector.detect(pcm.samples, pcm.sampleRate)
        val silence = assertNotNull(silences.singleOrNull(), "verwacht precies één stilte, kreeg $silences")

        val padding = SilenceConfig().paddingMs * US_PER_MS
        val tolerance = 20 * US_PER_MS

        assertEquals((US_PER_SECOND + padding).toDouble(), silence.startUs.toDouble(), tolerance.toDouble())
        assertEquals((2 * US_PER_SECOND - padding).toDouble(), silence.endUs.toDouble(), tolerance.toDouble())
        assertTrue(silence.rmsDb < -60f, "digitale stilte hoort diep onder de drempel te liggen")
    }

    @Test
    fun `de te behouden stukken zijn het complement van de stilte`() {
        val file = File(directory, "golden.wav")
        WavFixtures.write(file, goldenSignal(), RATE)
        val pcm = WavFixtures.read(file)

        val silences = SilenceDetector.detect(pcm.samples, pcm.sampleRate)
        val keeps = SilenceDetector.keepIntervals(silences, totalDurationUs = 3 * US_PER_SECOND)

        assertEquals(2, keeps.size)
        assertEquals(0L, keeps.first().first)
        assertEquals(3 * US_PER_SECOND - 1, keeps.last().last)
    }

    @Test
    fun `schrijven en lezen houdt het signaal binnen de kwantisatiefout`() {
        val file = File(directory, "roundtrip.wav")
        val original = goldenSignal()
        WavFixtures.write(file, original, RATE)

        val read = WavFixtures.read(file).samples

        assertEquals(original.size, read.size)
        val largestError = original.indices.maxOf { kotlin.math.abs(original[it] - read[it]) }
        assertTrue(largestError < 1f / 32_000f, "16 bits geeft hooguit een halve stap afwijking, was $largestError")
    }

    @Test
    fun `stereo wordt correct teruggelezen en gedownmixt`() {
        val file = File(directory, "stereo.wav")
        val mono = goldenSignal()
        val interleaved = FloatArray(mono.size * 2) { mono[it / 2] }
        WavFixtures.write(file, interleaved, RATE, channels = 2)

        val pcm = WavFixtures.read(file)
        assertEquals(2, pcm.channels)

        val downmixed = downmixToMono(pcm.samples, pcm.channels)
        assertEquals(mono.size, downmixed.size)
        assertEquals(1, SilenceDetector.detect(downmixed, pcm.sampleRate).size)
    }
}
