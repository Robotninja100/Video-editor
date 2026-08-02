package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Transcript
import nl.artifation.videoeditor.model.TranscriptSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun words(vararg spec: Pair<String, LongRange>) =
    spec.map { (text, range) -> Cue.Word(range.first, range.last, text) }

/** Woorden op een vast ritme, elk [stepUs] lang. */
private fun evenWords(text: String, stepUs: Long = 300_000L) =
    text.split(" ").mapIndexed { index, word ->
        Cue.Word(index * stepUs, (index + 1) * stepUs, word)
    }

class WrapTest {

    @Test
    fun `regels breken op woordgrenzen`() {
        val lines = CaptionLayout.wrap("dit is een test van het afbreken", maxCharsPerLine = 12)

        assertTrue(lines.all { it.length <= 12 }, "te lange regel in $lines")
        assertEquals("dit is een test van het afbreken", lines.joinToString(" "))
    }

    @Test
    fun `een woord langer dan de regel krijgt zijn eigen regel`() {
        val lines = CaptionLayout.wrap("kort onaanvaardbaarheidsverklaring kort", maxCharsPerLine = 10)

        assertTrue("onaanvaardbaarheidsverklaring" in lines, "woord is opgeknipt: $lines")
    }

    @Test
    fun `lege tekst levert geen regels op`() {
        assertEquals(emptyList(), CaptionLayout.wrap("", 10))
        assertEquals(emptyList(), CaptionLayout.wrap("   ", 10))
    }
}

class CaptionGroupingTest {

    @Test
    fun `woorden worden gegroepeerd tot leesbare blokken`() {
        val cues = CaptionLayout.fromWords(
            evenWords("dit is een langere zin die over meerdere blokken verdeeld moet worden"),
            CaptionConfig(maxCharsPerLine = 20, maxLines = 2),
        )

        assertTrue(cues.size > 1, "alles in één blok gepropt")
        for (cue in cues) {
            assertTrue(
                cue.text.replace("\n", " ").length <= 40,
                "blok te lang: '${cue.text}'",
            )
        }
    }

    @Test
    fun `een lange pauze forceert een nieuw blok`() {
        val cues = CaptionLayout.fromWords(
            words(
                "een" to 0L..200_000L,
                "twee" to 200_000L..400_000L,
                // Gat van 1 seconde.
                "drie" to 1_400_000L..1_600_000L,
            ),
            CaptionConfig(splitOnGapUs = 600_000L),
        )

        assertEquals(2, cues.size, "pauze hoort te splitsen: ${cues.map { it.text }}")
        assertEquals("een twee", cues[0].text)
        assertEquals("drie", cues[1].text)
    }

    @Test
    fun `een blok wordt opgeknipt als het te lang duurt`() {
        val cues = CaptionLayout.fromWords(
            evenWords("een twee drie vier vijf zes zeven acht", stepUs = 900_000L),
            CaptionConfig(maxDurationUs = 2_000_000L, splitOnGapUs = 10_000_000L),
        )

        assertTrue(cues.size > 1, "te lang blok is niet opgeknipt")
        for (cue in cues) {
            assertTrue(
                cue.endUs - cue.startUs <= 2_000_000L + 900_000L,
                "blok duurt te lang: ${cue.endUs - cue.startUs}us",
            )
        }
    }

    @Test
    fun `cues houden hun woorden voor karaoke-highlighting`() {
        val cues = CaptionLayout.fromWords(evenWords("een twee drie"))

        assertEquals(listOf("een", "twee", "drie"), cues.single().words.map { it.text })
    }

    @Test
    fun `cues overlappen nooit`() {
        val cues = CaptionLayout.fromWords(
            evenWords("een twee drie vier vijf zes zeven acht negen tien"),
            CaptionConfig(maxCharsPerLine = 10, maxLines = 1),
        )

        cues.zipWithNext { a, b ->
            assertTrue(a.endUs <= b.startUs, "overlap tussen '${a.text}' en '${b.text}'")
        }
    }

    @Test
    fun `zonder woorden komen er geen cues`() {
        assertEquals(emptyList(), CaptionLayout.fromWords(emptyList()))
    }
}

class CaptionDurationTest {

    @Test
    fun `een te kort blok wordt verlengd`() {
        val cues = CaptionLayout.fromWords(
            words("hoi" to 0L..100_000L),
            CaptionConfig(minDurationUs = 800_000L),
        )

        assertEquals(800_000L, cues.single().endUs, "te kort om te lezen, hoort verlengd")
    }

    @Test
    fun `verlengen gaat nooit over het volgende blok heen`() {
        val cues = CaptionLayout.fromWords(
            words(
                "hoi" to 0L..100_000L,
                "daar" to 900_000L..1_500_000L,
            ),
            CaptionConfig(minDurationUs = 2_000_000L, splitOnGapUs = 500_000L),
        )

        assertEquals(2, cues.size)
        assertTrue(cues[0].endUs <= cues[1].startUs, "eerste blok loopt over het tweede heen")
    }
}

/**
 * `maxLines` werd alleen als tekenbudget gebruikt (`maxCharsPerLine * maxLines`).
 * Dat is een benadering die te ruim uitvalt: woorden die net niet op een regel
 * passen, breken naar een extra regel terwijl het totaal binnen het budget bleef.
 */
class CaptionRegellimietTest {

    @Test
    fun `een blok blijft binnen het maximale aantal regels`() {
        val lange = List(6) { "woordvanwintigtekens" }
            .mapIndexed { i, w -> Cue.Word(i * 400_000L, (i + 1) * 400_000L, w) }

        val cues = CaptionLayout.fromWords(lange, CaptionConfig(maxCharsPerLine = 32, maxLines = 2))

        for (cue in cues) {
            assertTrue(
                cue.text.split("\n").size <= 2,
                "blok met ${cue.text.split("\n").size} regels: '${cue.text}'",
            )
        }
    }

    @Test
    fun `ook bij een regel per blok wordt de limiet gerespecteerd`() {
        val cues = CaptionLayout.fromWords(
            evenWords("een twee drie vier vijf zes"),
            CaptionConfig(maxCharsPerLine = 10, maxLines = 1),
        )

        assertTrue(cues.all { "\n" !in it.text }, "gevonden: ${cues.map { it.text }}")
    }
}

/**
 * Woordtijden zijn optioneel — niet elke dienst levert ze. Zonder terugval op de
 * segmenttijden leverde zo'n transcript stilzwijgend nul ondertitels op.
 */
class CaptionZonderWoordtijdenTest {

    @Test
    fun `een transcript met alleen segmenttijden levert toch cues op`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(0, 0L, 1_000_000L, "hallo daar"),
                TranscriptSegment(1, 1_200_000L, 2_400_000L, "en tot ziens"),
            ),
        )

        val cues = CaptionLayout.fromTranscript(transcript)

        // Twee korte segmenten dicht op elkaar horen gewoon samengevoegd te
        // worden; waar het om gaat is dat er überhaupt ondertitels uitkomen.
        assertTrue(cues.isNotEmpty(), "een transcript zonder woordtijden gaf niets")
        assertEquals(0L, cues.first().startUs)
        assertEquals(
            "hallo daar en tot ziens",
            cues.joinToString(" ") { it.text.replace("\n", " ") },
            "de tekst hoort volledig terug te komen",
        )
    }

    @Test
    fun `segmenten ver uit elkaar blijven aparte cues`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(0, 0L, 1_000_000L, "hallo daar"),
                TranscriptSegment(1, 5_000_000L, 6_000_000L, "en tot ziens"),
            ),
        )

        assertEquals(2, CaptionLayout.fromTranscript(transcript).size)
    }

    @Test
    fun `woordtijden krijgen voorrang als ze er zijn`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(
                    index = 0,
                    startUs = 0L,
                    endUs = 1_000_000L,
                    text = "hallo daar",
                    words = listOf(
                        Cue.Word(0L, 400_000L, "hallo"),
                        Cue.Word(400_000L, 1_000_000L, "daar"),
                    ),
                ),
            ),
        )

        assertEquals(2, CaptionLayout.fromTranscript(transcript).single().words.size)
    }

    @Test
    fun `een leeg transcript levert niets op`() {
        assertEquals(emptyList(), CaptionLayout.fromTranscript(Transcript()))
    }
}
