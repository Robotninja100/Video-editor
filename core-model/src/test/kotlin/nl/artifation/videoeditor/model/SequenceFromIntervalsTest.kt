package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val URI = "content://media/video/1"

private fun bouw(vararg intervals: LongRange) =
    sequenceFromIntervals(id = "video", sourceUri = URI, intervals = intervals.toList())

class SequenceFromIntervalsTest {

    @Test
    fun `elk interval wordt een clip met de juiste snijpunten`() {
        val sequence = bouw(0L until 1_000L, 2_000L until 3_000L)

        assertEquals(2, sequence.items.size)
        val eerste = assertIs<Clip>(sequence.items[0])
        val tweede = assertIs<Clip>(sequence.items[1])

        assertEquals(0L, eerste.inPointUs)
        assertEquals(1_000L, eerste.outPointUs)
        assertEquals(2_000L, tweede.inPointUs)
        assertEquals(3_000L, tweede.outPointUs)
    }

    @Test
    fun `de clipduur is precies de intervalduur`() {
        // `until` maakt een inclusief bereik van 0..999; de clip moet 1000 lang zijn.
        val clip = assertIs<Clip>(bouw(0L until 1_000L).items.single())

        assertEquals(1_000L, clip.durationUs, "één microseconde weglekken per knip is niet acceptabel")
    }

    @Test
    fun `de tijdlijn bevat geen gaten`() {
        val sequence = bouw(0L until 1_000L, 5_000L until 6_000L)

        assertTrue(sequence.items.all { it is Clip }, "weggeknipte stiltes horen te verdwijnen, niet leeg te blijven")
        assertEquals(2_000L, sequence.durationUs)
    }

    @Test
    fun `alle clips wijzen naar dezelfde bron`() {
        val sequence = bouw(0L until 1_000L, 2_000L until 3_000L)

        assertTrue(sequence.items.filterIsInstance<Clip>().all { it.sourceUri == URI })
    }

    @Test
    fun `de ids zijn afleidbaar en dus stabiel`() {
        val ids = bouw(0L until 1_000L, 2_000L until 3_000L)
            .items.filterIsInstance<Clip>().map { it.id }

        assertEquals(listOf("clip-0", "clip-1"), ids)
    }

    @Test
    fun `een eigen voorvoegsel werkt door in de ids`() {
        val sequence = sequenceFromIntervals("video", URI, listOf(0L until 10L), idPrefix = "stilte")

        assertEquals("stilte-0", assertIs<Clip>(sequence.items.single()).id)
    }

    @Test
    fun `zonder intervallen komt er een lege sequence uit`() {
        assertEquals(emptyList(), bouw().items)
    }

    @Test
    fun `lege intervallen worden overgeslagen`() {
        val sequence = bouw(0L until 0L, 1_000L until 2_000L)

        assertEquals(1, sequence.items.size)
    }
}

class MergeOverlappingTest {

    @Test
    fun `ongesorteerde invoer komt gesorteerd terug`() {
        val merged = mergeOverlapping(listOf(5_000L until 6_000L, 0L until 1_000L))

        assertEquals(listOf(0L until 1_000L, 5_000L until 6_000L), merged)
    }

    @Test
    fun `overlappende intervallen worden er één`() {
        // Twee analyses die elkaar deels dekken zouden anders hetzelfde beeld
        // twee keer op de tijdlijn zetten.
        val merged = mergeOverlapping(listOf(0L..1_000L, 500L..1_500L))

        assertEquals(listOf(0L..1_500L), merged)
    }

    @Test
    fun `een interval dat volledig in een ander ligt verdwijnt erin`() {
        val merged = mergeOverlapping(listOf(0L..2_000L, 500L..600L))

        assertEquals(listOf(0L..2_000L), merged)
    }

    @Test
    fun `aansluitende intervallen worden samengevoegd`() {
        // 0..999 en 1000..1999 zijn samen gewoon 0..1999; een knip ertussen
        // heeft niemand gevraagd.
        val merged = mergeOverlapping(listOf(0L until 1_000L, 1_000L until 2_000L))

        assertEquals(listOf(0L..1_999L), merged)
    }

    @Test
    fun `intervallen met een gat ertussen blijven gescheiden`() {
        val merged = mergeOverlapping(listOf(0L until 1_000L, 1_001L until 2_000L))

        assertEquals(2, merged.size)
    }

    @Test
    fun `een lege lijst blijft leeg`() {
        assertEquals(emptyList(), mergeOverlapping(emptyList()))
        assertEquals(emptyList(), mergeOverlapping(listOf(5L until 5L)))
    }

    @Test
    fun `samengevoegde intervallen leveren minder clips op`() {
        val sequence = bouw(0L..1_000L, 500L..1_500L)

        assertEquals(1, sequence.items.size, "overlap hoort niet twee keer in beeld te komen")
    }
}
