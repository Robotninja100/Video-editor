package nl.artifation.videoeditor.project

/**
 * Wat er bij het openen van een project moet gebeuren.
 *
 * De beslissing is expres een waarde en geen actie: de aanroeper (UI) bepaalt
 * of hij de gebruiker iets vraagt, en de logica blijft testbaar zonder opslag.
 */
public sealed interface RecoveryPlan {

    /** Er staat niets van dit project op de opslag. */
    public data class NotFound(val id: String) : RecoveryPlan

    /** Alleen het goede bestand; gewoon openen. */
    public data class OpenSaved(val saved: ProjectSummary) : RecoveryPlan

    /**
     * Er staat een nieuwere, leesbare autosave klaar: de app is waarschijnlijk
     * gecrasht na de laatste bewuste opslag.
     *
     * [saved] is null als het goede bestand ontbreekt of zelf stuk is; dan is de
     * autosave het enige wat er nog is.
     */
    public data class OfferAutosave(
        val saved: ProjectSummary?,
        val autosave: ProjectSummary,
        val newerByMs: Long,
    ) : RecoveryPlan

    /** Het goede bestand openen en de autosave weggooien. */
    public data class DiscardAutosave(
        val saved: ProjectSummary,
        val reason: DiscardReason,
    ) : RecoveryPlan

    /** Er is wel iets, maar niets ervan is te lezen. */
    public data class Unrecoverable(val id: String, val detail: String) : RecoveryPlan

    public enum class DiscardReason {
        /** Half weggeschreven of anderszins stuk — precies waar de MAIN-kopie voor is. */
        AUTOSAVE_ONLEESBAAR,

        /** De autosave is niet nieuwer dan het opgeslagen bestand. */
        AUTOSAVE_NIET_NIEUWER,
    }
}

/**
 * Bepaalt bij het openen welk bestand gebruikt moet worden.
 *
 * Leidende regel: een autosave wordt pas serieus genomen als hij volledig te
 * lezen is én nieuwer. Een half geschreven autosave verliest dus altijd van het
 * opgeslagen bestand, en promoveren gebeurt pas ná die controle — zie
 * [ProjectRepository.open].
 */
public object CrashRecovery {

    /**
     * @throws UnsupportedSchemaVersionException als een van beide bestanden uit
     *   een nieuwere app komt. Dat is geen herstelbaar geval: terugvallen op een
     *   ouder bestand zou de nieuwere versie stilzwijgend weggooien.
     */
    public fun inspect(store: ProjectStore, id: String): RecoveryPlan {
        val savedText = store.readRaw(id, ProjectSlot.MAIN)
        val autosaveText = store.readRaw(id, ProjectSlot.AUTOSAVE)
        if (savedText == null && autosaveText == null) return RecoveryPlan.NotFound(id)

        val saved = savedText?.let { summaryOrNull(it) }
        val autosave = autosaveText?.let { summaryOrNull(it) }

        if (autosave == null) {
            return when {
                saved != null && autosaveText == null -> RecoveryPlan.OpenSaved(saved)
                saved != null -> RecoveryPlan.DiscardAutosave(
                    saved,
                    RecoveryPlan.DiscardReason.AUTOSAVE_ONLEESBAAR,
                )

                else -> RecoveryPlan.Unrecoverable(id, "geen enkel leesbaar bestand")
            }
        }

        return when {
            saved == null -> RecoveryPlan.OfferAutosave(null, autosave, newerByMs = 0L)
            autosave.lastModifiedMs > saved.lastModifiedMs -> RecoveryPlan.OfferAutosave(
                saved,
                autosave,
                newerByMs = autosave.lastModifiedMs - saved.lastModifiedMs,
            )

            else -> RecoveryPlan.DiscardAutosave(
                saved,
                RecoveryPlan.DiscardReason.AUTOSAVE_NIET_NIEUWER,
            )
        }
    }

    private fun summaryOrNull(text: String): ProjectSummary? =
        try {
            ProjectCodec.decodeSummary(text)
        } catch (e: CorruptProjectException) {
            null
        }
}
