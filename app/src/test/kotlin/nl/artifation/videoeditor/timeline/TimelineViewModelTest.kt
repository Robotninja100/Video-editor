package nl.artifation.videoeditor.timeline

import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.Gap
import nl.artifation.videoeditor.model.Sequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests op de toestandslogica van het tijdlijnscherm.
 *
 * De bewerkingen zelf zijn al getest in `:core-model`; wat hier overblijft is wat
 * het scherm eromheen doet — selectie, playhead, geschiedenis, en het afvangen van
 * sleepbewegingen die buiten de grenzen vallen. Dat laatste is precies het soort
 * ding dat op een toestel als crash aan het licht komt en hier als test.
 *
 * Geen Robolectric nodig: er zit geen Android-API in deze klasse.
 */
class TimelineViewModelTest {

    private fun sequence() = Sequence(
        id = "main",
        items = listOf(
            Clip(id = "a", sourceUri = "demo://a", inPointUs = 0L, outPointUs = 4_000_000L),
            Gap(durationUs = 1_000_000L),
            Clip(id = "b", sourceUri = "demo://b", inPointUs = 2_000_000L, outPointUs = 8_000_000L),
        ),
    )

    @Test
    fun `de playhead blijft binnen de tijdlijn`() {
        val viewModel = TimelineViewModel(sequence())

        viewModel.scrubTo(-5_000_000L)
        assertEquals(0L, viewModel.state.value.playheadUs)

        viewModel.scrubTo(999_000_000L)
        assertEquals(viewModel.state.value.durationUs, viewModel.state.value.playheadUs)
    }

    @Test
    fun `splitsen op de playhead voegt een item toe`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.scrubTo(2_000_000L)

        viewModel.splitAtPlayhead()

        assertEquals(4, viewModel.state.value.sequence.items.size)
        assertTrue(viewModel.state.value.canUndo)
    }

    @Test
    fun `splitsen op een itemgrens verandert niets en vult de geschiedenis niet`() {
        val viewModel = TimelineViewModel(sequence())
        // Precies op de grens tussen de eerste clip en het gat.
        viewModel.scrubTo(4_000_000L)

        viewModel.splitAtPlayhead()

        assertEquals(3, viewModel.state.value.sequence.items.size)
        assertFalse(
            "een bewerking die niets doet hoort geen undo-stap op te leveren",
            viewModel.state.value.canUndo,
        )
    }

    @Test
    fun `verwijderen laat de rest opschuiven en heft de selectie op`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.select(0)

        viewModel.deleteSelected()

        val state = viewModel.state.value
        // Het gat en clip b blijven over, en na normalized() staat het gat vooraan.
        assertEquals(2, state.sequence.items.size)
        assertNull(state.selectedIndex)
    }

    @Test
    fun `zonder selectie doet verwijderen niets`() {
        val viewModel = TimelineViewModel(sequence())

        viewModel.deleteSelected()

        assertEquals(3, viewModel.state.value.sequence.items.size)
        assertFalse(viewModel.state.value.canUndo)
    }

    @Test
    fun `trimmen verschuift het in-punt`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.select(0)

        viewModel.trimSelected(deltaUs = 500_000L, leadingEdge = true)

        val clip = viewModel.state.value.sequence.items[0] as Clip
        assertEquals(500_000L, clip.inPointUs)
        assertEquals(4_000_000L, clip.outPointUs)
    }

    /**
     * De reden dat dit een test is en geen aanname: `trimClip` gooit bij een
     * ongeldig bereik. Een gebruiker die de rand voorbij het uitpunt sleept hoort
     * een randje te voelen, geen crash.
     */
    @Test
    fun `te ver trimmen wordt genegeerd in plaats van gegooid`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.select(0)

        viewModel.trimSelected(deltaUs = 99_000_000L, leadingEdge = true)

        val clip = viewModel.state.value.sequence.items[0] as Clip
        assertEquals(0L, clip.inPointUs)
        assertFalse(viewModel.state.value.canUndo)
    }

    @Test
    fun `trimmen tot voor nul wordt genegeerd`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.select(0)

        viewModel.trimSelected(deltaUs = -1_000_000L, leadingEdge = true)

        assertEquals(0L, (viewModel.state.value.sequence.items[0] as Clip).inPointUs)
        assertFalse(viewModel.state.value.canUndo)
    }

    @Test
    fun `een gat verplaatsen doet niets`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.select(1)

        viewModel.moveSelectedTo(0L)

        assertFalse(
            "alleen clips zijn te verplaatsen, gaten niet",
            viewModel.state.value.canUndo,
        )
    }

    @Test
    fun `een clip verplaatsen naar een negatieve tijd landt op nul`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.select(2)

        viewModel.moveSelectedTo(-3_000_000L)

        assertEquals(0L, viewModel.state.value.itemStartsUs.first())
    }

    @Test
    fun `ongedaan maken en opnieuw doorlopen de geschiedenis`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.scrubTo(2_000_000L)
        viewModel.splitAtPlayhead()
        assertEquals(4, viewModel.state.value.sequence.items.size)

        viewModel.undo()
        assertEquals(3, viewModel.state.value.sequence.items.size)
        assertTrue(viewModel.state.value.canRedo)

        viewModel.redo()
        assertEquals(4, viewModel.state.value.sequence.items.size)
    }

    @Test
    fun `de selectie vervalt als het item na een undo niet meer bestaat`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.scrubTo(2_000_000L)
        viewModel.splitAtPlayhead()
        viewModel.select(3)

        viewModel.undo()

        assertNull(
            "index 3 bestaat niet meer na het terugdraaien van de splitsing",
            viewModel.state.value.selectedIndex,
        )
    }

    @Test
    fun `de playhead schuift mee naar binnen als de tijdlijn korter wordt`() {
        val viewModel = TimelineViewModel(sequence())
        viewModel.scrubTo(10_000_000L)
        viewModel.select(2)

        viewModel.deleteSelected()

        val state = viewModel.state.value
        assertTrue(
            "playhead ${state.playheadUs} valt buiten de nieuwe duur ${state.durationUs}",
            state.playheadUs <= state.durationUs,
        )
    }
}
