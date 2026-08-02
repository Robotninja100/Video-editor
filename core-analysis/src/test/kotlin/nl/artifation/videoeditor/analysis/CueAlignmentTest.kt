package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.Us
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun ms(value: Long): Us = value * US_PER_MS

private fun cue(startMs: Long, endMs: Long, text: String = "tekst", words: List<Cue.Word> = emptyList()) =
    Cue(startUs = ms(startMs), endUs = ms(endMs), text = text, words = words)

private fun word(startMs: Long, endMs: Long, text: String) =
    Cue.Word(startUs = ms(startMs), endUs = ms(endMs), text = text)

/** Stilte van [startMs] tot [endMs]; spraak begint dus op [endMs]. */
private fun stilte(startMs: Long, endMs: Long) =
    SilenceInterval(startUs = ms(startMs), endUs = ms(endMs), rmsDb = -70f)

class CueAlignmentTest {

    @Test
    fun `een cue die te vroeg begint wordt naar het begin van de spraak getrokken`() {
        // Stilte tot 1000 ms, dus daar begint de spraak. De transcriptie zat 80 ms mis.
        val aligned = CueAlignment.align(
            cues = listOf(cue(920, 3000)),
            silences = listOf(stilte(0, 1000)),
        )

        assertEquals(ms(1000), aligned.single().startUs)
    }

    @Test
    fun `een cue die te laat eindigt wordt naar het einde van de spraak getrokken`() {
        val aligned = CueAlignment.align(
            cues = listOf(cue(1000, 3080)),
            silences = listOf(stilte(3000, 4000)),
        )

        assertEquals(ms(3000), aligned.single().endUs)
    }

    @Test
    fun `beide grenzen worden in één keer bijgesteld`() {
        val aligned = CueAlignment.align(
            cues = listOf(cue(920, 3080)),
            silences = listOf(stilte(0, 1000), stilte(3000, 4000)),
        ).single()

        assertEquals(ms(1000), aligned.startUs)
        assertEquals(ms(3000), aligned.endUs)
    }

    @Test
    fun `een grens zonder stilte in de buurt blijft staan`() {
        val original = cue(5000, 7000)
        val aligned = CueAlignment.align(
            cues = listOf(original),
            silences = listOf(stilte(0, 1000)),
        )

        assertSame(original, aligned.single(), "niets te corrigeren, dus ook niets aan te raken")
    }

    @Test
    fun `de maximale verschuiving wordt gerespecteerd`() {
        val original = cue(1400, 3000)
        val aligned = CueAlignment.align(
            cues = listOf(original),
            silences = listOf(stilte(0, 1000)),
            config = AlignConfig(maxShiftUs = ms(300)),
        )

        assertSame(original, aligned.single(), "400 ms is verder dan toegestaan")
    }

    @Test
    fun `een correctie die de cue zou omdraaien gaat niet door`() {
        // De enige stilte begint vóór de cue: naar het einde snappen zou het
        // eindpunt vóór het beginpunt leggen.
        val original = cue(1000, 1100)
        val aligned = CueAlignment.align(
            cues = listOf(original),
            silences = listOf(stilte(900, 950)),
        ).single()

        assertTrue(aligned.endUs > aligned.startUs, "was ${aligned.startUs}..${aligned.endUs}")
    }

    @Test
    fun `een correctie die de cue onleesbaar kort maakt gaat niet door`() {
        val aligned = CueAlignment.align(
            cues = listOf(cue(1000, 3000)),
            // Zou het einde naar 1100 trekken: 100 ms is te kort om te lezen.
            silences = listOf(stilte(1100, 1200)),
            config = AlignConfig(maxShiftUs = ms(2000), minCueDurationUs = ms(400)),
        ).single()

        assertTrue(
            aligned.endUs - aligned.startUs >= ms(400),
            "duur was ${aligned.endUs - aligned.startUs}",
        )
    }

    @Test
    fun `een korte cue wordt door de correctie niet nog korter`() {
        // Duur 200 ms, al onder de ondergrens. Het begin naar de spraak trekken
        // zou er 120 ms van maken. Dan is te vroeg in beeld komen het kleinere
        // kwaad: onleesbaar kort is erger dan tachtig milliseconde te vroeg.
        val original = cue(920, 1120)
        val aligned = CueAlignment.align(
            cues = listOf(original),
            silences = listOf(stilte(0, 1000)),
            config = AlignConfig(minCueDurationUs = ms(400)),
        ).single()

        assertEquals(original, aligned)
    }

    @Test
    fun `een korte cue mag door de correctie wel langer worden`() {
        // Zelfde lengte, maar nu ligt het begin ná de spraak: de cue wordt
        // langer in plaats van korter, en dat is altijd goed.
        val aligned = CueAlignment.align(
            cues = listOf(cue(1050, 1250)),
            silences = listOf(stilte(0, 1000)),
            config = AlignConfig(minCueDurationUs = ms(400)),
        ).single()

        assertEquals(ms(1000), aligned.startUs)
    }

    @Test
    fun `cues gaan nooit over hun voorganger heen`() {
        val aligned = CueAlignment.align(
            cues = listOf(cue(1000, 2000), cue(2000, 4000)),
            // Zou de tweede cue naar 1800 trekken, dus vóór het einde van de eerste.
            silences = listOf(stilte(1500, 1800)),
        )

        assertTrue(
            aligned[1].startUs >= aligned[0].endUs,
            "overlap: ${aligned[0].endUs} > ${aligned[1].startUs}",
        )
    }

    @Test
    fun `woordtijden lopen mee met de nieuwe grenzen`() {
        val aligned = CueAlignment.align(
            cues = listOf(
                cue(
                    920, 3000,
                    words = listOf(
                        word(920, 1400, "hallo"),
                        word(1400, 3000, "daar"),
                    ),
                ),
            ),
            silences = listOf(stilte(0, 1000)),
        ).single()

        assertEquals(ms(1000), aligned.words.first().startUs, "het eerste woord begint met de cue mee")
        assertEquals(2, aligned.words.size)
    }

    @Test
    fun `woorden die buiten de nieuwe grenzen vallen verdwijnen`() {
        val aligned = CueAlignment.align(
            cues = listOf(
                cue(
                    920, 3000,
                    words = listOf(
                        word(920, 990, "ruis"),
                        word(1200, 3000, "spraak"),
                    ),
                ),
            ),
            // Trekt het begin naar 1000; het eerste woord ligt daar volledig voor.
            silences = listOf(stilte(0, 1000)),
        ).single()

        assertEquals(listOf("spraak"), aligned.words.map { it.text })
    }

    @Test
    fun `de tekst blijft onaangeroerd`() {
        val aligned = CueAlignment.align(
            cues = listOf(cue(920, 3000, text = "precies deze tekst")),
            silences = listOf(stilte(0, 1000)),
        ).single()

        assertEquals("precies deze tekst", aligned.text)
    }

    @Test
    fun `zonder stiltes verandert er niets`() {
        val cues = listOf(cue(1000, 2000), cue(2000, 3000))

        assertSame(cues, CueAlignment.align(cues, emptyList()))
    }

    @Test
    fun `zonder cues komt er niets uit`() {
        assertEquals(emptyList(), CueAlignment.align(emptyList(), listOf(stilte(0, 1000))))
    }

    @Test
    fun `het aantal cues verandert nooit`() {
        val cues = List(5) { index -> cue(index * 1000L, index * 1000L + 800) }
        val silences = List(5) { index -> stilte(index * 1000L + 820, index * 1000L + 1000) }

        assertEquals(cues.size, CueAlignment.align(cues, silences).size)
    }

    @Test
    fun `ongesorteerde stiltes leveren hetzelfde resultaat op`() {
        val silences = listOf(stilte(3000, 4000), stilte(0, 1000))
        val gesorteerd = CueAlignment.align(listOf(cue(920, 3080)), silences.sortedBy { it.startUs })

        assertEquals(gesorteerd, CueAlignment.align(listOf(cue(920, 3080)), silences))
    }

    @Test
    fun `onzinnige instellingen worden geweigerd`() {
        assertFailsWith<IllegalArgumentException> { AlignConfig(maxShiftUs = -1L) }
        assertFailsWith<IllegalArgumentException> { AlignConfig(minCueDurationUs = -1L) }
    }
}
