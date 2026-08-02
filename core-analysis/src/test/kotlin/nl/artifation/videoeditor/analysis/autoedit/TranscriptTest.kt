package nl.artifation.videoeditor.analysis.autoedit

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.US_PER_SECOND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Eén cue per seconde, zodat index n loopt van n tot n+1 seconde. */
private fun cues(count: Int) = List(count) { index ->
    Cue(
        startUs = index.toLong() * US_PER_SECOND,
        endUs = (index + 1).toLong() * US_PER_SECOND,
        text = "regel $index",
    )
}

class NumberedTranscriptTest {

    @Test
    fun `elke cue krijgt zijn index ervoor`() {
        assertEquals(
            "0: regel 0\n1: regel 1\n2: regel 2",
            Transcript.numbered(cues(3)),
        )
    }

    @Test
    fun `regeleindes in een cue worden platgeslagen`() {
        val messy = listOf(Cue(0L, US_PER_SECOND, "  eerste\n  tweede  "))

        assertEquals(
            "0: eerste tweede", Transcript.numbered(messy),
            "een cue over twee regels zou de nummering in de prompt laten verspringen",
        )
    }

    @Test
    fun `een leeg transcript levert een lege tekst op`() {
        assertEquals("", Transcript.numbered(emptyList()))
    }
}

class ParseIndicesTest {

    @Test
    fun `een kale lijst wordt gelezen`() {
        assertEquals(listOf(12, 13, 27), Transcript.parseIndices("[12, 13, 27]"))
    }

    @Test
    fun `een lijst in een codefence met uitleg eromheen wordt gelezen`() {
        val raw = """
            Ik heb de herhalingen eruit gehaald.

            ```json
            [0, 2, 5]
            ```

            De rest liep dubbel.
        """.trimIndent()

        assertEquals(listOf(0, 2, 5), Transcript.parseIndices(raw))
    }

    @Test
    fun `getallen in de uitleg tellen niet mee zodra er een lijst is`() {
        val raw = "Van de 42 regels houd ik er 3 over: [1, 4, 9]"

        assertEquals(listOf(1, 4, 9), Transcript.parseIndices(raw))
    }

    @Test
    fun `zonder blokhaken worden de losse getallen genomen`() {
        assertEquals(listOf(1, 2, 3), Transcript.parseIndices("Houd regels 1, 2 en 3."))
    }

    @Test
    fun `een lege lijst blijft leeg`() {
        assertEquals(emptyList(), Transcript.parseIndices("[]"))
    }

    @Test
    fun `negatieve getallen worden gelezen zodat de validatie ze kan afwijzen`() {
        assertEquals(listOf(-1, 3), Transcript.parseIndices("[-1, 3]"))
    }
}

class ValidateTest {

    @Test
    fun `indices buiten bereik worden apart gemeld`() {
        val selection = Transcript.validate(listOf(-1, 0, 2, 99), cueCount = 3)

        assertEquals(listOf(0, 2), selection.kept)
        assertEquals(listOf(-1, 99), selection.outOfRange)
        assertTrue(selection.hasRejections)
    }

    @Test
    fun `dubbele indices worden er één keer gehouden`() {
        val selection = Transcript.validate(listOf(1, 1, 2), cueCount = 3)

        assertEquals(listOf(1, 2), selection.kept)
        assertEquals(listOf(1), selection.duplicates)
    }

    @Test
    fun `een omgekeerde volgorde wordt gesorteerd`() {
        val selection = Transcript.validate(listOf(5, 1, 3), cueCount = 6)

        assertEquals(listOf(1, 3, 5), selection.kept)
        assertFalse(selection.hasRejections, "verkeerde volgorde is geen fout, alleen onhandig")
    }

    @Test
    fun `een lege selectie is geldig en leeg`() {
        val selection = Transcript.validate(emptyList(), cueCount = 5)

        assertEquals(emptyList(), selection.kept)
        assertFalse(selection.hasRejections)
    }

    @Test
    fun `zonder cues kan er niets overblijven`() {
        val selection = Transcript.validate(listOf(0, 1), cueCount = 0)

        assertEquals(emptyList(), selection.kept)
        assertEquals(listOf(0, 1), selection.outOfRange)
    }
}

class KeepIntervalsTest {

    @Test
    fun `opeenvolgende regels smelten samen tot één interval`() {
        val selection = Transcript.validate(listOf(0, 1, 2), cueCount = 5)

        assertEquals(
            listOf(0L until 3 * US_PER_SECOND),
            Transcript.toKeepIntervals(selection, cues(5)),
        )
    }

    @Test
    fun `een gat in de selectie geeft twee intervallen`() {
        val selection = Transcript.validate(listOf(0, 1, 3), cueCount = 5)

        assertEquals(
            listOf(0L until 2 * US_PER_SECOND, 3 * US_PER_SECOND until 4 * US_PER_SECOND),
            Transcript.toKeepIntervals(selection, cues(5)),
        )
    }

    @Test
    fun `een lege selectie levert geen intervallen op`() {
        val selection = Transcript.validate(emptyList(), cueCount = 5)

        assertEquals(emptyList(), Transcript.toKeepIntervals(selection, cues(5)))
    }

    @Test
    fun `losse regels blijven losse intervallen`() {
        val selection = Transcript.validate(listOf(0, 2, 4), cueCount = 5)

        assertEquals(3, Transcript.toKeepIntervals(selection, cues(5)).size)
    }
}

class BrokenModelOutputTest {

    @Test
    fun `opzettelijk kapotte uitvoer levert een bruikbare selectie op`() {
        val raw = "[3, 3, -2, 1, 999, 0]"

        val selection = Transcript.select(raw, cueCount = 5)

        assertEquals(listOf(0, 1, 3), selection.kept)
        assertEquals(listOf(-2, 999), selection.outOfRange)
        assertEquals(listOf(3), selection.duplicates)
    }

    @Test
    fun `uitvoer zonder enig getal levert niets op in plaats van een exception`() {
        val selection = Transcript.select("Sorry, dat kan ik niet.", cueCount = 5)

        assertEquals(emptyList(), selection.kept)
        assertFalse(selection.hasRejections)
    }

    @Test
    fun `de intervallen kloppen ook na het opschonen`() {
        val selection = Transcript.select("[2, 1, 1, 42]", cueCount = 4)

        assertEquals(
            listOf(US_PER_SECOND until 3 * US_PER_SECOND),
            Transcript.toKeepIntervals(selection, cues(4)),
            "1 en 2 volgen op elkaar en horen één interval te worden",
        )
    }
}
