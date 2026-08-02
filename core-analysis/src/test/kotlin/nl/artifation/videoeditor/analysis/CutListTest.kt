package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Transcript
import nl.artifation.videoeditor.model.TranscriptSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun transcript(count: Int, segmentUs: Long = 1_000_000L) = Transcript(
    segments = (0 until count).map {
        TranscriptSegment(
            index = it,
            startUs = it * segmentUs,
            endUs = (it + 1) * segmentUs,
            text = "segment $it",
        )
    },
)

class CutListValidationTest {

    @Test
    fun `geldige indices worden geselecteerd`() {
        val result = CutList.fromIndices(transcript(5), listOf(1, 3))

        assertEquals(listOf(1, 3), result.kept.map { it.index })
        assertFalse(result.hasRejections)
    }

    @Test
    fun `verzonnen indices worden geweigerd`() {
        val result = CutList.fromIndices(transcript(3), listOf(0, 7, -1, 2))

        assertEquals(listOf(0, 2), result.kept.map { it.index })
        assertEquals(listOf(7, -1), result.rejected)
        assertTrue(result.hasRejections)
    }

    @Test
    fun `dubbele indices leveren één segment op`() {
        val result = CutList.fromIndices(transcript(3), listOf(1, 1, 1))
        assertEquals(listOf(1), result.kept.map { it.index })
    }

    @Test
    fun `herhalingen worden apart gemeld, niet stil samengevoegd`() {
        val result = CutList.fromIndices(transcript(3), listOf(1, 1, 2, 1))

        assertEquals(listOf(1, 2), result.kept.map { it.index })
        assertEquals(listOf(1, 1), result.duplicates, "twee herhalingen van index 1")
        assertTrue(result.hasRejections, "een model dat zichzelf herhaalt hoort zichtbaar te zijn")
        assertEquals(emptyList(), result.rejected, "een herhaling is geen verzinsel")
    }

    @Test
    fun `omgekeerde volgorde wordt genormaliseerd naar tijdvolgorde`() {
        val result = CutList.fromIndices(transcript(4), listOf(3, 0, 2))
        assertEquals(listOf(0, 2, 3), result.kept.map { it.index })
    }

    @Test
    fun `volledig onzinnige output levert een lege cut-list op`() {
        val result = CutList.fromIndices(transcript(3), listOf(99, 100))

        assertEquals(emptyList(), result.kept)
        assertEquals(listOf(99, 100), result.rejected)
    }

    @Test
    fun `lege selectie is geen fout`() {
        val result = CutList.fromIndices(transcript(3), emptyList())
        assertEquals(emptyList(), result.kept)
        assertFalse(result.hasRejections)
    }
}

class CutListIntervalsTest {

    @Test
    fun `aaneengesloten segmenten worden samengevoegd`() {
        val segments = CutList.fromIndices(transcript(5), listOf(0, 1, 2)).kept

        assertEquals(listOf(0L until 3_000_000L), CutList.toIntervals(segments))
    }

    @Test
    fun `losse segmenten blijven aparte intervallen`() {
        val segments = CutList.fromIndices(transcript(5), listOf(0, 3)).kept

        assertEquals(
            listOf(0L until 1_000_000L, 3_000_000L until 4_000_000L),
            CutList.toIntervals(segments),
        )
    }

    @Test
    fun `kleine gaatjes worden overbrugd`() {
        val segments = listOf(
            TranscriptSegment(0, 0L, 1_000_000L, "a"),
            TranscriptSegment(1, 1_100_000L, 2_000_000L, "b"),
        )

        assertEquals(
            listOf(0L until 2_000_000L),
            CutList.toIntervals(segments, gapToleranceUs = 200_000L),
            "100ms gat valt binnen de tolerantie",
        )
        assertEquals(
            2,
            CutList.toIntervals(segments, gapToleranceUs = 50_000L).size,
            "met strakkere tolerantie blijven het twee intervallen",
        )
    }

    @Test
    fun `lege selectie levert geen intervallen op`() {
        assertEquals(emptyList(), CutList.toIntervals(emptyList()))
    }
}

class TranscriptTest {

    @Test
    fun `genummerd transcript is wat de LLM te zien krijgt`() {
        assertEquals(
            "[0] segment 0\n[1] segment 1\n[2] segment 2",
            transcript(3).numbered(),
        )
    }

    @Test
    fun `duur is het laatste eindpunt`() {
        assertEquals(3_000_000L, transcript(3).durationUs)
        assertEquals(0L, Transcript().durationUs)
    }
}
