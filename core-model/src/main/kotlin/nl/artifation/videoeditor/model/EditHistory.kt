package nl.artifation.videoeditor.model

/**
 * Undo/redo als snapshots in een deque.
 *
 * Dat kan hier zo simpel omdat alles in dit model een immutable data class is:
 * een snapshot deelt zijn ongewijzigde onderdelen met de vorige versie, dus honderd
 * stappen geschiedenis kosten geen honderd projecten aan geheugen. Een diff- of
 * commandostack zou hier alleen maar complexiteit toevoegen.
 *
 * Generiek in [T] zodat hij ook op één [Sequence] gebruikt kan worden, niet alleen
 * op een heel [Project]. Niet thread-safe: bedoeld voor de UI-thread.
 */
public class EditHistory<T>(
    initial: T,
    /** Oudere stappen dan dit vallen aan de onderkant weg. */
    public val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    init {
        require(maxDepth > 0) { "maxDepth moet positief zijn, was $maxDepth" }
    }

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    public var current: T = initial
        private set

    public val canUndo: Boolean get() = past.isNotEmpty()
    public val canRedo: Boolean get() = future.isNotEmpty()

    /** Aantal beschikbare stappen, handig om in de UI te tonen en om te testen. */
    public val undoDepth: Int get() = past.size
    public val redoDepth: Int get() = future.size

    /**
     * Legt een nieuwe toestand vast. Dit verwerpt de redo-tak: na een bewerking is
     * er geen "vooruit" meer om naar terug te keren.
     *
     * Een push die niets verandert wordt genegeerd, zodat de gebruiker niet twee
     * keer moet undo'en voor één zichtbare stap.
     */
    public fun push(next: T) {
        if (next == current) return

        past.addLast(current)
        if (past.size > maxDepth) past.removeFirst()
        future.clear()
        current = next
    }

    /** Eén stap terug. Geeft de nieuwe huidige toestand terug, ook als er niets te undo'en viel. */
    public fun undo(): T {
        val previous = past.removeLastOrNull() ?: return current
        future.addLast(current)
        current = previous
        return current
    }

    /** Eén stap vooruit, na een [undo]. */
    public fun redo(): T {
        val next = future.removeLastOrNull() ?: return current
        past.addLast(current)
        current = next
        return current
    }

    /** Gooit de geschiedenis weg en houdt de huidige toestand. Voor "project opgeslagen". */
    public fun clear() {
        past.clear()
        future.clear()
    }

    public companion object {
        public const val DEFAULT_MAX_DEPTH: Int = 100
    }
}
