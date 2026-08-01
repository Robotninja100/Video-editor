package nl.artifation.videoeditor.project

/**
 * Soort bewerking, want niet elke bewerking verdient dezelfde haast.
 *
 * Dit is geen fijnmazige lijst van editorcommando's: alleen het verschil dat
 * invloed heeft op wanneer er geschreven wordt, telt hier.
 */
public enum class EditKind {
    /**
     * Slepen, trimmen, een schuifje verzetten: komt in bursts van tientallen
     * per seconde. Hier is debouncen het hele punt.
     */
    CONTINUOUS,

    /**
     * Knippen, verwijderen, invoegen, een effect toevoegen: één handeling die
     * meteen waarde heeft en zelden herhaald wordt.
     */
    STRUCTURAL,

    /** Naam of exportinstelling: goedkoop en zeldzaam, mag direct mee. */
    METADATA,
}

public data class AutosaveConfig(
    /** Rust na een continue bewerking voordat er geschreven mag worden. */
    val quietPeriodMs: Long = 1_500L,
    /** Structurele bewerkingen wachten korter; die wil je niet kwijtraken. */
    val structuralDelayMs: Long = 300L,
    /** Harde bovengrens: nooit langer dan dit zonder schrijven, hoe druk het ook is. */
    val maxIntervalMs: Long = 30_000L,
    /**
     * Ondergrens tussen twee schrijfacties. Zonder deze zou een lange sleep met
     * telkens net genoeg pauze alsnog tientallen schrijfacties opleveren.
     */
    val minIntervalMs: Long = 1_000L,
    /** Hoe lang later er opnieuw gekeken wordt als er al een schrijfactie loopt. */
    val retryDelayMs: Long = 200L,
) {
    init {
        require(quietPeriodMs >= 0) { "quietPeriodMs moet >= 0 zijn, was $quietPeriodMs" }
        require(structuralDelayMs >= 0) { "structuralDelayMs moet >= 0 zijn, was $structuralDelayMs" }
        require(minIntervalMs >= 0) { "minIntervalMs moet >= 0 zijn, was $minIntervalMs" }
        require(retryDelayMs > 0) { "retryDelayMs moet positief zijn, was $retryDelayMs" }
        require(maxIntervalMs >= quietPeriodMs) {
            "maxIntervalMs ($maxIntervalMs) moet >= quietPeriodMs ($quietPeriodMs) zijn"
        }
        require(maxIntervalMs >= minIntervalMs) {
            "maxIntervalMs ($maxIntervalMs) moet >= minIntervalMs ($minIntervalMs) zijn"
        }
    }

    public fun debounceMs(kind: EditKind): Long = when (kind) {
        EditKind.CONTINUOUS -> quietPeriodMs
        EditKind.STRUCTURAL -> structuralDelayMs
        EditKind.METADATA -> structuralDelayMs
    }
}

/**
 * Alles wat de beslissing nodig heeft. Geen klok: de tijd komt als parameter
 * binnen bij [AutosavePolicy.decide], zodat de beslissing deterministisch is.
 */
public data class AutosaveState(
    val hasUnsavedChanges: Boolean = false,
    val lastEditKind: EditKind? = null,
    val lastEditAtMs: Long = 0L,
    /** null zolang dit project nog nooit is weggeschreven. */
    val lastWriteAtMs: Long? = null,
    val writeInProgress: Boolean = false,
)

public enum class AutosaveReason {
    NIETS_TE_SCHRIJVEN,

    /** De gebruiker is even gestopt met bewerken. */
    RUSTPAUZE,

    /** De bovengrens is bereikt; er wordt midden in het bewerken geschreven. */
    BOVENGRENS,

    /** Er is net geschreven; nog even niet opnieuw. */
    ONDERGRENS,

    /** Er loopt al een schrijfactie. */
    SCHRIJFACTIE_BEZIG,
}

public sealed interface AutosaveDecision {
    public val reason: AutosaveReason

    /** Nu wegschrijven. */
    public data class Write(override val reason: AutosaveReason) : AutosaveDecision

    /** Later terugkomen; [untilMs] is het vroegste zinvolle moment. */
    public data class Wait(
        val untilMs: Long,
        override val reason: AutosaveReason,
    ) : AutosaveDecision

    /** Er valt niets te doen. */
    public data class Skip(override val reason: AutosaveReason) : AutosaveDecision
}

/**
 * Beslist wanneer er automatisch opgeslagen wordt.
 *
 * Puur rekenwerk, geen timers en geen systeemklok. De aanroeper heeft al een
 * frameklok of een handler; die mag zelf op [AutosaveDecision.Wait.untilMs]
 * terugkomen. Daardoor is het gedrag hier in één test na te spelen in plaats
 * van uit te zitten.
 */
public object AutosavePolicy {

    public fun decide(
        state: AutosaveState,
        nowMs: Long,
        config: AutosaveConfig = AutosaveConfig(),
    ): AutosaveDecision {
        if (!state.hasUnsavedChanges) return AutosaveDecision.Skip(AutosaveReason.NIETS_TE_SCHRIJVEN)
        if (state.writeInProgress) {
            return AutosaveDecision.Wait(
                nowMs + config.retryDelayMs,
                AutosaveReason.SCHRIJFACTIE_BEZIG,
            )
        }

        // Zonder eerdere schrijfactie loopt de bovengrens vanaf de eerste bewerking:
        // een nieuw project mag niet dertig seconden ongeschreven blijven puur
        // omdat er nog nooit iets is opgeslagen.
        val hardDeadlineMs = (state.lastWriteAtMs ?: state.lastEditAtMs) + config.maxIntervalMs
        val quietDeadlineMs =
            state.lastEditAtMs + config.debounceMs(state.lastEditKind ?: EditKind.CONTINUOUS)

        val wantedMs = minOf(quietDeadlineMs, hardDeadlineMs)
        val earliestMs = state.lastWriteAtMs?.plus(config.minIntervalMs)
        val targetMs = if (earliestMs != null) maxOf(wantedMs, earliestMs) else wantedMs

        if (nowMs >= targetMs) {
            val reason =
                if (nowMs >= hardDeadlineMs) AutosaveReason.BOVENGRENS else AutosaveReason.RUSTPAUZE
            return AutosaveDecision.Write(reason)
        }
        val reason = when {
            earliestMs != null && targetMs == earliestMs && earliestMs > wantedMs ->
                AutosaveReason.ONDERGRENS

            targetMs == hardDeadlineMs && hardDeadlineMs < quietDeadlineMs ->
                AutosaveReason.BOVENGRENS

            else -> AutosaveReason.RUSTPAUZE
        }
        return AutosaveDecision.Wait(targetMs, reason)
    }

    /** Toestand na een bewerking. */
    public fun onEdit(state: AutosaveState, kind: EditKind, nowMs: Long): AutosaveState =
        state.copy(hasUnsavedChanges = true, lastEditKind = kind, lastEditAtMs = nowMs)

    /**
     * Toestand na een geslaagde schrijfactie.
     *
     * Bewerkingen die tijdens het schrijven binnenkwamen (`lastEditAtMs` na
     * [startedAtMs]) blijven onopgeslagen staan — anders raakt precies de
     * bewerking kwijt die net te laat was.
     */
    public fun onWritten(state: AutosaveState, startedAtMs: Long, finishedAtMs: Long): AutosaveState =
        state.copy(
            hasUnsavedChanges = state.lastEditAtMs > startedAtMs,
            lastWriteAtMs = finishedAtMs,
            writeInProgress = false,
        )
}
