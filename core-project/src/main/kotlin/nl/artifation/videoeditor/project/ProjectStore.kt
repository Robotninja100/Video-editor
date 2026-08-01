package nl.artifation.videoeditor.project

/**
 * Waar een projectbestand staat.
 *
 * Twee losse bestanden per project in plaats van één: een autosave mag het
 * laatst bewust opgeslagen bestand nooit aanraken. Gaat het schrijven van de
 * autosave mis, dan is [MAIN] nog steeds de montage van gisteren en niet de
 * helft daarvan.
 */
public enum class ProjectSlot {
    /** Het laatste bewust opgeslagen bestand. */
    MAIN,

    /** Het automatisch weggeschreven bestand; kan nieuwer zijn dan [MAIN]. */
    AUTOSAVE,
}

/**
 * Opslag van projectbestanden.
 *
 * De ruwe laag (`readRaw`/`writeRaw`) werkt met tekst, niet met objecten. Zo
 * blijft migratie en herstel mogelijk voor bestanden die deze app-versie nog
 * niet — of niet meer — kan decoderen.
 *
 * Implementaties moeten [writeRaw] ondeelbaar maken: na een afgebroken schrijf
 * staat er óf de oude inhoud, óf de nieuwe, nooit een mengsel.
 */
public interface ProjectStore {

    /** Alle project-ids waarvan iets op de opslag staat, in willekeurige volgorde. */
    public fun ids(): List<String>

    public fun readRaw(id: String, slot: ProjectSlot = ProjectSlot.MAIN): String?

    public fun writeRaw(id: String, slot: ProjectSlot, text: String)

    public fun deleteSlot(id: String, slot: ProjectSlot)

    /** Verwijdert het project inclusief zijn autosave. */
    public fun delete(id: String)

    public fun has(id: String, slot: ProjectSlot = ProjectSlot.MAIN): Boolean =
        readRaw(id, slot) != null

    public fun save(file: ProjectFile, slot: ProjectSlot = ProjectSlot.MAIN) {
        writeRaw(file.project.id, slot, ProjectCodec.encode(file))
    }

    public fun load(id: String, slot: ProjectSlot = ProjectSlot.MAIN): ProjectFile {
        val text = readRaw(id, slot) ?: throw ProjectNotFoundException(id)
        return ProjectCodec.decode(text)
    }

    /**
     * Koppen van alle projecten, nieuwste eerst.
     *
     * Alleen [ProjectSlot.MAIN]: de lijst toont de laatste bewust opgeslagen
     * toestand. Of er een nieuwere autosave klaarstaat, is een vraag bij het
     * openen — zie [CrashRecovery].
     *
     * Eén stukgelopen project mag de lijst niet slopen, dus onleesbare
     * bestanden worden overgeslagen. Ze verdwijnen niet uit beeld: zie
     * [unreadableIds], zodat de app kan melden dat er iets mis is in plaats van
     * het project stilletjes te laten verdampen.
     */
    public fun summaries(): List<ProjectSummary> =
        ids().mapNotNull { id -> summaryOrNull(id) }
            .sortedByDescending { it.lastModifiedMs }

    /** Projecten met een bestand dat niet te lezen is — stuk, of uit een nieuwere app. */
    public fun unreadableIds(): List<String> =
        ids().filter { id -> readRaw(id, ProjectSlot.MAIN) != null && summaryOrNull(id) == null }

    private fun summaryOrNull(id: String): ProjectSummary? {
        val text = readRaw(id, ProjectSlot.MAIN) ?: return null
        return try {
            ProjectCodec.decodeSummary(text)
        } catch (e: ProjectException) {
            null
        }
    }
}

/**
 * Opslag in het geheugen, voor tests en previews.
 *
 * Schrijven is hier per constructie ondeelbaar (één `put` in een map); een
 * echte bestandsimplementatie moet daarvoor via een tijdelijk bestand met een
 * rename werken.
 */
public class InMemoryProjectStore : ProjectStore {

    private data class Key(val id: String, val slot: ProjectSlot)

    private val files = LinkedHashMap<Key, String>()
    private val failing = mutableSetOf<Key>()

    /** Aantal geslaagde schrijfacties; handig om debounce-gedrag te controleren. */
    public var writeCount: Int = 0
        private set

    override fun ids(): List<String> = files.keys.map { it.id }.distinct()

    override fun readRaw(id: String, slot: ProjectSlot): String? = files[Key(id, slot)]

    override fun writeRaw(id: String, slot: ProjectSlot, text: String) {
        val key = Key(id, slot)
        if (failing.remove(key)) {
            throw IllegalStateException("gesimuleerde schrijffout voor $id/$slot")
        }
        files[key] = text
        writeCount++
    }

    override fun deleteSlot(id: String, slot: ProjectSlot) {
        files.remove(Key(id, slot))
    }

    override fun delete(id: String) {
        ProjectSlot.entries.forEach { files.remove(Key(id, it)) }
    }

    /** Laat de eerstvolgende schrijfactie naar dit slot falen, zoals een volle schijf. */
    public fun failNextWrite(id: String, slot: ProjectSlot) {
        failing.add(Key(id, slot))
    }
}
