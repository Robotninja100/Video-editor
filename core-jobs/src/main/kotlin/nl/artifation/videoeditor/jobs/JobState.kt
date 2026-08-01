package nl.artifation.videoeditor.jobs

import kotlinx.serialization.Serializable

/**
 * De levensloop van een taak.
 *
 * `Queued → Running → (Succeeded | Failed | Cancelled)`, met `Paused` als zijspoor.
 * Opnieuw proberen loopt via `Running → Queued` en dus nooit via `Failed`: dat
 * houdt de drie eindtoestanden écht eindig.
 */
@Serializable
public enum class JobState {
    Queued,
    Running,
    Paused,
    Succeeded,
    Failed,
    Cancelled,
    ;

    /** Uit een eindtoestand komt een taak nooit meer terug. */
    public val isTerminal: Boolean
        get() = this == Succeeded || this == Failed || this == Cancelled
}

/**
 * De toegestane overgangen, op één plek.
 *
 * Alles wat hier niet in staat wordt geweigerd. Dat is de kern van deze module:
 * een taak die van `Succeeded` terug naar `Running` kan levert dubbel werk op —
 * een tweede export over hetzelfde bestand — en dat merk je pas veel later.
 */
public object JobStateMachine {

    private val allowed: Map<JobState, Set<JobState>> = mapOf(
        JobState.Queued to setOf(JobState.Running, JobState.Paused, JobState.Cancelled),
        // Running → Queued is het terugleggen: na een herstelbare fout, of bij het
        // laden van een wachtrij waarvan het proces tijdens het draaien stierf.
        JobState.Running to setOf(
            JobState.Succeeded,
            JobState.Failed,
            JobState.Cancelled,
            JobState.Paused,
            JobState.Queued,
        ),
        // Hervatten zet een taak terug in de wachtrij; de wachtrij bepaalt daarna
        // zelf of hij aan de beurt is. Paused → Running zou de volgorde omzeilen.
        JobState.Paused to setOf(JobState.Queued, JobState.Cancelled),
        JobState.Succeeded to emptySet(),
        JobState.Failed to emptySet(),
        JobState.Cancelled to emptySet(),
    )

    public fun allowedFrom(state: JobState): Set<JobState> = allowed.getValue(state)

    public fun isAllowed(from: JobState, to: JobState): Boolean = to in allowedFrom(from)
}

/** Geweigerde overgang. Bewust een fout en geen stille no-op. */
public class IllegalJobTransition(
    public val jobId: String,
    public val from: JobState,
    public val to: JobState,
) : IllegalStateException("taak $jobId: overgang $from -> $to is niet toegestaan")
