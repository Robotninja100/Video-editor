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
        require(structuralDelayMs >= 0) {
            "structuralDelayMs moet >= 0 zijn, was $structuralDelayMs"
        }
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
    /**
     * Wanneer de huidige reeks onopgeslagen bewerkingen begon.
     *
     * Dit is het anker voor de bovengrens zolang er nog nooit geschreven is.
     * Zonder dit veld hing die grens aan de láátste bewerking en schoof hij dus
     * bij elke bewerking mee — een nieuw project dat continu bewerkt wordt, zou
     * daardoor nooit worden weggeschreven. Juist daar staat er niets op schijf
     * om op terug te vallen.
     */
    val firstEditSinceWriteAtMs: Long? = null,
    val writeInProgress: Boolean = false,
)

public enum class AutosaveReason {
    NOTHING_TO_SAVE,

    /** De gebruiker is even gestopt met bewerken. */
    QUIET_PERIOD,

    /** De bovengrens is bereikt; er wordt midden in het bewerken geschreven. */
    MAX_INTERVAL,

    /** Er is net geschreven; nog even niet opnieuw. */
    MIN_INTERVAL,

    /** Er loopt al een schrijfactie. */
    WRITE_IN_PROGRESS,
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
        if (!state.hasUnsavedChanges) return AutosaveDecision.Skip(AutosaveReason.NOTHING_TO_SAVE)
        if (state.writeInProgress) {
            return AutosaveDecision.Wait(
                nowMs + config.retryDelayMs,
                AutosaveReason.WRITE_IN_PROGRESS,
            )
        }

        val hardDeadlineMs = hardDeadlineOf(state, config)
        val quietDeadlineMs =
            state.lastEditAtMs + config.debounceMs(state.lastEditKind ?: EditKind.CONTINUOUS)

        val wantedMs = minOf(quietDeadlineMs, hardDeadlineMs)
        val earliestMs = state.lastWriteAtMs?.plus(config.minIntervalMs)
        val targetMs = if (earliestMs != null) maxOf(wantedMs, earliestMs) else wantedMs

        if (nowMs >= targetMs) {
            val reason = if (nowMs >= hardDeadlineMs) {
                AutosaveReason.MAX_INTERVAL
            } else {
                AutosaveReason.QUIET_PERIOD
            }
            return AutosaveDecision.Write(reason)
        }
        val reason = when {
            earliestMs != null && targetMs == earliestMs && earliestMs > wantedMs ->
                AutosaveReason.MIN_INTERVAL

            targetMs == hardDeadlineMs && hardDeadlineMs < quietDeadlineMs ->
                AutosaveReason.MAX_INTERVAL

            else -> AutosaveReason.QUIET_PERIOD
        }
        return AutosaveDecision.Wait(targetMs, reason)
    }

    /** Toestand na een bewerking. */
    /**
     * De bovengrens: uiterlijk wanneer er geschreven moet zijn.
     *
     * Zonder eerdere schrijfactie loopt die vanaf de éérste onopgeslagen
     * bewerking, niet vanaf de laatste. Anders schuift de grens bij elk
     * bewerkinkje mee en wordt er tijdens doorlopend bewerken nooit geschreven —
     * precies bij een nieuw project, waar niets op schijf staat om op terug te
     * vallen.
     */
    private fun hardDeadlineOf(state: AutosaveState, config: AutosaveConfig): Long {
        val anchorMs = state.lastWriteAtMs
            ?: state.firstEditSinceWriteAtMs
            ?: state.lastEditAtMs
        return anchorMs + config.maxIntervalMs
    }

    public fun onEdit(state: AutosaveState, kind: EditKind, nowMs: Long): AutosaveState =
        state.copy(
            hasUnsavedChanges = true,
            lastEditKind = kind,
            lastEditAtMs = nowMs,
            // Alleen de éérste bewerking van een reeks zet het anker.
            firstEditSinceWriteAtMs = state.firstEditSinceWriteAtMs.takeIf { state.hasUnsavedChanges }
                ?: nowMs,
        )

    /**
     * Toestand na een geslaagde schrijfactie.
     *
     * Bewerkingen die tijdens het schrijven binnenkwamen (`lastEditAtMs` na
     * [startedAtMs]) blijven onopgeslagen staan — anders raakt precies de
     * bewerking kwijt die net te laat was.
     */
    public fun onWritten(
        state: AutosaveState,
        startedAtMs: Long,
        finishedAtMs: Long,
    ): AutosaveState =
        state.copy(
            hasUnsavedChanges = state.lastEditAtMs > startedAtMs,
            lastWriteAtMs = finishedAtMs,
            // Bewerkingen die tijdens het schrijven binnenkwamen beginnen een
            // nieuwe reeks; de rest heeft geen anker meer nodig.
            firstEditSinceWriteAtMs = state.lastEditAtMs.takeIf { it > startedAtMs },
            writeInProgress = false,
        )
}
