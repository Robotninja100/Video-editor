package nl.artifation.videoeditor.model

/**
 * Undo/redo als snapshots.
 *
 * Bij deze projectgrootte is dat prima en het scheelt een command-pattern: omdat
 * `Project` uit immutable data classes bestaat, delen opeenvolgende snapshots het
 * grootste deel van hun structuur. Alleen de gewijzigde tak wordt echt gekopieerd.
 */
public class UndoStack(
    initial: Project,
    private val limit: Int = 100,
) {
    init {
        require(limit >= 1) { "limit moet minstens 1 zijn" }
    }

    private val past = ArrayDeque<Project>()
    private val future = ArrayDeque<Project>()

    public var current: Project = initial
        private set

    public val canUndo: Boolean get() = past.isNotEmpty()
    public val canRedo: Boolean get() = future.isNotEmpty()
    public val undoDepth: Int get() = past.size

    /**
     * Legt een nieuwe toestand vast. Een bewerking die niets verandert wordt
     * genegeerd, anders vult de geschiedenis zich met lege stappen.
     */
    public fun push(next: Project) {
        if (next == current) return
        past.addLast(current)
        if (past.size > limit) past.removeFirst()
        future.clear()
        current = next
    }

    /** Handig voor bewerkingen: `stack.edit { it.copy(...) }`. */
    public fun edit(transform: (Project) -> Project) {
        push(transform(current))
    }

    public fun undo(): Project {
        val previous = past.removeLastOrNull() ?: return current
        future.addLast(current)
        current = previous
        return current
    }

    public fun redo(): Project {
        val next = future.removeLastOrNull() ?: return current
        past.addLast(current)
        current = next
        return current
    }
}
