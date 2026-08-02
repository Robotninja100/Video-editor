package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UndoStackTest {

    private fun project(id: String) = Project(id, listOf(Sequence("main")))

    @Test
    fun `undo en redo lopen door de geschiedenis`() {
        val stack = UndoStack(project("v1"))
        stack.push(project("v2"))
        stack.push(project("v3"))

        assertEquals("v2", stack.undo().id)
        assertEquals("v1", stack.undo().id)
        assertEquals("v2", stack.redo().id)
        assertEquals("v3", stack.redo().id)
    }

    @Test
    fun `een bewerking die niets verandert wordt genegeerd`() {
        val stack = UndoStack(project("v1"))
        stack.push(project("v1"))

        assertEquals(0, stack.undoDepth, "identieke toestand hoort geen stap te zijn")
        assertTrue(!stack.canUndo)
    }

    @Test
    fun `een nieuwe bewerking wist de redo-tak`() {
        val stack = UndoStack(project("v1"))
        stack.push(project("v2"))
        stack.undo()
        stack.push(project("v3"))

        assertTrue(!stack.canRedo, "redo hoort weg te zijn na een nieuwe bewerking")
        assertEquals("v3", stack.current.id)
    }

    @Test
    fun `undo op een lege geschiedenis doet niets`() {
        val stack = UndoStack(project("v1"))

        assertEquals("v1", stack.undo().id)
        assertEquals("v1", stack.redo().id)
    }

    @Test
    fun `de geschiedenis blijft binnen de limiet`() {
        val stack = UndoStack(project("v0"), limit = 3)
        repeat(10) { stack.push(project("v${it + 1}")) }

        assertEquals(3, stack.undoDepth)
    }

    @Test
    fun `edit past een transformatie toe`() {
        val stack = UndoStack(project("v1"))
        stack.edit { it.copy(id = "v2") }

        assertEquals("v2", stack.current.id)
        assertTrue(stack.canUndo)
    }
}
