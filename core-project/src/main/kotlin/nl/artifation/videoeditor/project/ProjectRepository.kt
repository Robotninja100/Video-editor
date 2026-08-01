package nl.artifation.videoeditor.project

import nl.artifation.videoeditor.model.Project

/**
 * De projectlaag zoals de app hem gebruikt: openen met herstel, opslaan,
 * autosaven, hernoemen, dupliceren en verwijderen.
 *
 * Alle tijden komen als parameter binnen; deze klasse leest nooit de klok.
 */
public class ProjectRepository(
    private val store: ProjectStore,
) {

    private var busyWithId: String? = null

    /** Of er op dit moment een schrijfactie loopt; voedt [AutosaveState.writeInProgress]. */
    public val isWriting: Boolean get() = busyWithId != null

    public fun list(): List<ProjectSummary> = store.summaries()

    public fun create(project: Project, name: String, nowMs: Long): ProjectFile {
        val file = ProjectFile.of(project, name, nowMs)
        save(file)
        return file
    }

    /**
     * Schrijft het goede bestand en ruimt de autosave op.
     *
     * Die opruiming is niet cosmetisch: laat je een oudere autosave staan, dan
     * biedt de volgende start hem aan als "nieuwer dan het opgeslagen bestand"
     * zodra de klok of de volgorde ook maar iets afwijkt.
     */
    public fun save(file: ProjectFile): ProjectFile = exclusively(file.project.id) {
        store.save(file, ProjectSlot.MAIN)
        store.deleteSlot(file.project.id, ProjectSlot.AUTOSAVE)
        file
    }

    /** Schrijft alleen het autosave-slot; [ProjectSlot.MAIN] blijft ongemoeid. */
    public fun autosave(file: ProjectFile): ProjectFile = exclusively(file.project.id) {
        store.save(file, ProjectSlot.AUTOSAVE)
        file
    }

    /** De herstelbeslissing, zodat de UI de gebruiker kan laten kiezen. */
    public fun plan(id: String): RecoveryPlan = CrashRecovery.inspect(store, id)

    /**
     * Opent een project volgens [plan].
     *
     * @param acceptAutosave de keuze van de gebruiker bij
     *   [RecoveryPlan.OfferAutosave]. Wordt de autosave geaccepteerd, dan wordt
     *   hij pas naar [ProjectSlot.MAIN] gepromoveerd nadat hij volledig
     *   gedecodeerd is — een half bestand kan zo nooit het goede overschrijven.
     */
    public fun open(id: String, acceptAutosave: Boolean = false): ProjectFile =
        when (val plan = plan(id)) {
            is RecoveryPlan.NotFound -> throw ProjectNotFoundException(id)

            is RecoveryPlan.OpenSaved -> store.load(id, ProjectSlot.MAIN)

            is RecoveryPlan.DiscardAutosave -> {
                store.deleteSlot(id, ProjectSlot.AUTOSAVE)
                store.load(id, ProjectSlot.MAIN)
            }

            // Zonder goed bestand is de autosave het enige wat er is; dan valt er
            // niets te kiezen en wordt hij hoe dan ook gepromoveerd.
            is RecoveryPlan.OfferAutosave ->
                if (acceptAutosave || plan.saved == null) promoteAutosave(id)
                else store.load(id, ProjectSlot.MAIN)

            is RecoveryPlan.Unrecoverable ->
                throw CorruptProjectException("project '$id' is niet te openen: ${plan.detail}")
        }

    /** Gooit de autosave weg; de gebruiker koos voor het opgeslagen bestand. */
    public fun discardAutosave(id: String) {
        store.deleteSlot(id, ProjectSlot.AUTOSAVE)
    }

    public fun rename(id: String, name: String, nowMs: Long): ProjectFile =
        save(store.load(id, ProjectSlot.MAIN).renamed(name, nowMs))

    /**
     * Kopieert een project onder een nieuwe id.
     *
     * De clips binnen het project houden hun ids: die zijn alleen binnen één
     * project betekenisvol, en ze gelijk houden scheelt het opnieuw analyseren
     * van hetzelfde bronmateriaal (sidecars hangen aan de bron, niet aan de clip).
     */
    public fun duplicate(id: String, newId: String, name: String, nowMs: Long): ProjectFile {
        require(newId != id) { "een duplicaat moet een andere id krijgen dan '$id'" }
        val original = store.load(id, ProjectSlot.MAIN)
        val copy = ProjectFile.of(
            project = original.project.copy(id = newId),
            name = name,
            lastModifiedMs = nowMs,
            thumbnailKey = original.summary.thumbnailKey,
        )
        return save(copy)
    }

    public fun delete(id: String) {
        store.delete(id)
    }

    /**
     * Decodeer eerst, schrijf daarna. Faalt het decoderen — een afgekapte
     * autosave — dan is [ProjectSlot.MAIN] nog onaangeroerd.
     */
    private fun promoteAutosave(id: String): ProjectFile {
        val recovered = store.load(id, ProjectSlot.AUTOSAVE)
        return save(recovered)
    }

    private fun <T> exclusively(id: String, block: () -> T): T {
        val busy = busyWithId
        if (busy != null) throw ConcurrentWriteException(id, busy)
        busyWithId = id
        try {
            return block()
        } finally {
            busyWithId = null
        }
    }
}
