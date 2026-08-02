package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditHistoryTest {

    @Test
    fun `een verse geschiedenis kan nergens heen`() {
        val history = EditHistory("a")

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals("a", history.undo(), "undo op een lege stack laat de toestand met rust")
        assertEquals("a", history.redo())
    }

    @Test
    fun `undo en redo lopen de stappen in volgorde af`() {
        val history = EditHistory("a")
        history.push("b")
        history.push("c")

        assertEquals("b", history.undo())
        assertEquals("a", history.undo())
        assertFalse(history.canUndo)

        assertEquals("b", history.redo())
        assertEquals("c", history.redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun `een nieuwe bewerking verwerpt de redo-tak`() {
        val history = EditHistory("a")
        history.push("b")
        history.undo()
        assertTrue(history.canRedo)

        history.push("c")

        assertFalse(history.canRedo, "na een nieuwe stap is er geen vooruit meer")
        assertEquals("c", history.current)
        assertEquals("a", history.undo())
    }

    @Test
    fun `een push die niets verandert wordt genegeerd`() {
        val history = EditHistory("a")
        history.push("a")

        assertFalse(history.canUndo, "anders moet de gebruiker twee keer undo'en voor één stap")
        assertEquals(0, history.undoDepth)
    }

    @Test
    fun `oudere stappen vallen weg bij het bereiken van maxDepth`() {
        val history = EditHistory(0, maxDepth = 3)
        for (value in 1..10) history.push(value)

        assertEquals(3, history.undoDepth)
        assertEquals(10, history.current)

        repeat(3) { history.undo() }
        assertEquals(7, history.current, "verder terug dan drie stappen kan niet meer")
        assertFalse(history.canUndo)
    }

    @Test
    fun `clear houdt de huidige toestand en gooit de rest weg`() {
        val history = EditHistory("a")
        history.push("b")
        history.undo()

        history.clear()

        assertEquals("a", history.current)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `maxDepth moet positief zijn`() {
        assertFailsWith<IllegalArgumentException> { EditHistory("a", maxDepth = 0) }
    }

    @Test
    fun `werkt op projecten via de bestaande bewerkingen`() {
        val start = Project(
            id = "p",
            sequences = listOf(
                Sequence(
                    id = "main",
                    items = listOf(
                        Clip(id = "a", sourceUri = "file:///a.mp4", inPointUs = 0L, outPointUs = 2 * US_PER_SECOND),
                    ),
                ),
            ),
        )
        val history = EditHistory(start)

        val split = start.mainSequence.splitAt(US_PER_SECOND)
        history.push(start.copy(sequences = listOf(split)))

        assertEquals(2, history.current.mainSequence.items.size)
        assertEquals(1, history.undo().mainSequence.items.size)
        assertEquals(start.durationUs, history.current.durationUs, "undo raakt de duur niet kwijt")
    }
}
