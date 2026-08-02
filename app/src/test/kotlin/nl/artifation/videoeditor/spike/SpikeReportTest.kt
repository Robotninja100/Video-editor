package nl.artifation.videoeditor.spike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests op het oordeel zelf.
 *
 * De meting draait alleen op een toestel, maar de vraag "wanneer is dit geslaagd"
 * is gewone logica — en het is precies de logica die je niet stilletjes verkeerd
 * wilt hebben, want die bepaalt of fase 1 mag beginnen. Een poort die te makkelijk
 * openstaat is erger dan geen poort.
 */
class SpikeReportTest {

    private fun parity(vararg scores: Double?) =
        ParityResult(
            samples = scores.mapIndexed { index, score ->
                ParitySample(timeUs = index * 1_000_000L, ssim = score)
            },
            note = null,
        )

    private fun blur(
        vararg samples: Triple<Double, Double, Double?>,
        inPointUs: Long = 0L,
    ) = MaskedBlurResult(
        label = "test",
        inPointUs = inPointUs,
        samples = samples.mapIndexed { index, (inside, outside, naive) ->
            BlurSample(
                clipTimeUs = index * 1_000_000L,
                sourceTimeUs = inPointUs + index * 1_000_000L,
                insideSharpness = inside,
                outsideSharpness = outside,
                naiveSharpness = naive,
            )
        },
    )

    @Test
    fun `pariteit slaagt bij hoge scores`() {
        assertTrue(parity(0.97, 0.96, 0.98).passed)
    }

    @Test
    fun `een enkele lage score laat pariteit zakken, ook als het gemiddelde goed is`() {
        // Gemiddeld 0,92 maar één tijdstip op 0,70: dat is geen pariteit, dat is
        // een gemiddelde dat een probleem verstopt.
        assertFalse(parity(0.99, 0.99, 0.70).passed)
    }

    @Test
    fun `een ontbrekend frame laat pariteit zakken`() {
        assertFalse(
            "een gat in het bewijs is geen bewijs",
            parity(0.98, null, 0.98).passed,
        )
    }

    @Test
    fun `pariteit zonder metingen slaagt nooit`() {
        assertFalse(parity().passed)
        assertFalse(ParityResult(emptyList(), note = "speler kwam niet op gang").passed)
    }

    @Test
    fun `masked blur slaagt als de cirkel duidelijk scherper is`() {
        assertTrue(blur(Triple(30.0, 6.0, null), Triple(28.0, 5.0, null)).passed)
    }

    @Test
    fun `masked blur zakt als binnen en buiten nauwelijks verschillen`() {
        // Verhouding 1,2× — dat kan meetruis zijn, en dus geen bewijs dat het mask
        // iets doet.
        assertFalse(blur(Triple(12.0, 10.0, null)).passed)
    }

    @Test
    fun `het slechtste tijdstip telt, niet het gemiddelde`() {
        assertFalse(
            "synchroon over de volle duur betekent op elk tijdstip",
            blur(Triple(40.0, 5.0, null), Triple(11.0, 10.0, null)).passed,
        )
    }

    /**
     * Dit is de test die de fout uit het bouwplan nabootst: als het in-punt
     * onderweg verloren gaat, staat de scherpe plek waar de cirkel op *cliptijd*
     * zou staan. De scherpte binnen is dan niet hoger dan daar.
     */
    @Test
    fun `masked blur op een getrimde clip zakt als het in-punt genegeerd wordt`() {
        val genegeerd = blur(
            Triple(30.0, 5.0, 28.0),
            inPointUs = 3_000_000L,
        )

        assertFalse(
            "de scherpe plek staat even goed op de cliptijd-positie; dat is geen synchronisatie",
            genegeerd.passed,
        )
    }

    @Test
    fun `masked blur op een getrimde clip slaagt als de brontijd wint`() {
        val correct = blur(
            Triple(30.0, 5.0, 8.0),
            Triple(32.0, 6.0, 9.0),
            inPointUs = 3_000_000L,
        )

        assertTrue(correct.passed)
    }

    @Test
    fun `een gestrande meting is nooit geslaagd`() {
        val report = SpikeReport(
            parity = parity(0.99, 0.99),
            blur = blur(Triple(30.0, 5.0, null)),
            blurTrimmed = blur(Triple(30.0, 5.0, 8.0), inPointUs = 3_000_000L),
            samples = emptyList(),
            failure = "encoder gaf het op",
        )

        assertFalse(report.passed)
        assertTrue(report.verdict.contains("geen uitslag"))
    }

    @Test
    fun `de poort staat pas open als alle drie de metingen slagen`() {
        fun report(trimmed: MaskedBlurResult) = SpikeReport(
            parity = parity(0.99, 0.98),
            blur = blur(Triple(30.0, 5.0, null)),
            blurTrimmed = trimmed,
            samples = emptyList(),
            failure = null,
        )

        assertTrue(report(blur(Triple(30.0, 5.0, 8.0), inPointUs = 3_000_000L)).passed)
        assertFalse(report(blur(Triple(30.0, 5.0, 28.0), inPointUs = 3_000_000L)).passed)
    }
}
