package nl.artifation.videoeditor.thermal

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Een blok werk: `[start, endExclusive)` in werkeenheden. Wat een eenheid is,
 * bepaalt de aanroeper — frames, seconden audio, of scènes.
 */
@Serializable
public data class WorkChunk(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "start moet >= 0 zijn, was $start" }
        require(endExclusive > start) { "leeg blok: $start..$endExclusive" }
    }

    val size: Int get() = endExclusive - start

    val indices: IntRange get() = start until endExclusive
}

/** Wat de scheduler als volgende stap voorstelt. */
public sealed interface ChunkPlan {

    /** Werk dit blok af en meld het terug met [ChunkScheduler.complete]. */
    public data class Work(val chunk: WorkChunk, val status: ThermalStatus) : ChunkPlan

    /** Wacht [waitMs] en vraag daarna opnieuw met een verse meting. */
    public data class Pause(
        val waitMs: Long,
        val reason: PauseReason,
        val status: ThermalStatus,
    ) : ChunkPlan

    /** Definitief einde: het toestel gaat uit. Later opnieuw vragen heeft geen zin. */
    public data class Stopped(val reason: PauseReason, val status: ThermalStatus) : ChunkPlan

    /** Alle eenheden zijn af. */
    public data object Done : ChunkPlan
}

/**
 * Alles wat nodig is om een klus later te hervatten. Bewust zonder het blok dat
 * op dit moment onderhanden is: een blok dat niet is teruggemeld, telt als niet
 * gedaan. Na herstel wordt het opnieuw uitgegeven — liever één blok dubbel dan
 * één blok kwijt.
 */
@Serializable
public data class SchedulerState(
    val totalUnits: Int,
    val completedUnits: Int = 0,
    val thermal: ThermalState = ThermalState(),
    /** Gemeten werktijd over [measuredUnits] eenheden; samen de basis voor de tijdschatting. */
    val workedMs: Long = 0L,
    val measuredUnits: Int = 0,
    val chunksCompleted: Int = 0,
) {
    init {
        require(totalUnits >= 0) { "totalUnits moet >= 0 zijn, was $totalUnits" }
        require(completedUnits in 0..totalUnits) {
            "completedUnits ($completedUnits) moet tussen 0 en totalUnits ($totalUnits) liggen"
        }
        require(workedMs >= 0L) { "workedMs moet >= 0 zijn, was $workedMs" }
        require(measuredUnits >= 0) { "measuredUnits moet >= 0 zijn, was $measuredUnits" }
        require(chunksCompleted >= 0) { "chunksCompleted moet >= 0 zijn, was $chunksCompleted" }
    }
}

/**
 * Deelt een lange klus (bijvoorbeeld 18.000 frames analyseren) op in blokken,
 * past de blokgrootte aan op de thermische terugkoppeling, houdt de voortgang
 * bij en kan hervatten vanaf het laatst voltooide blok.
 *
 * De lus bij de aanroeper is bewust simpel en synchroon:
 * ```
 * while (true) {
 *     when (val plan = scheduler.next(meet(), klok())) {
 *         is ChunkPlan.Work -> { doe(plan.chunk); scheduler.complete(plan.chunk, klok()) }
 *         is ChunkPlan.Pause -> slaap(plan.waitMs)
 *         is ChunkPlan.Stopped -> break
 *         ChunkPlan.Done -> break
 *     }
 * }
 * ```
 * Geen threads, geen timers, geen systeemklok: de tijd komt binnen als parameter.
 */
public class ChunkScheduler private constructor(
    public val policy: ThermalPolicy,
    private var state: SchedulerState,
) {
    public constructor(
        totalUnits: Int,
        policy: ThermalPolicy = ThermalPolicy(),
    ) : this(policy, SchedulerState(totalUnits = totalUnits))

    private val governor = ThermalGovernor(policy, state.thermal)

    /** Het uitgegeven maar nog niet teruggemelde blok; hooguit één tegelijk. */
    private var outstanding: WorkChunk? = null

    /**
     * Wanneer het onderhanden blok is uitgegeven, of null als deze planner het
     * blok niet zelf heeft uitgegeven.
     *
     * Na herstel uit een momentopname houdt de aanroeper vaak nog het blok van
     * vóór de herstart vast. Zou dat als "uitgegeven op tijdstip 0" tellen, dan
     * wordt de hele wandkloktijd sinds epoch als werktijd geboekt en meldt de
     * schatting iets als "nog ongeveer 122 minuten".
     */
    private var outstandingIssuedAtMs: Long? = null

    private var lastDecision: ThermalDecision? = null
    private var lastDecisionAtMs: Long = 0L

    public val totalUnits: Int get() = state.totalUnits
    public val completedUnits: Int get() = state.completedUnits
    public val remainingUnits: Int get() = state.totalUnits - state.completedUnits
    public val chunksCompleted: Int get() = state.chunksCompleted
    public val isDone: Boolean get() = state.completedUnits >= state.totalUnits
    public val thermalTransitions: Int get() = governor.transitions

    /** Loopt monotoon op; een klus zonder werk staat meteen op 1.0. */
    public val progress: Double
        get() = if (state.totalUnits == 0) 1.0 else state.completedUnits.toDouble() / state.totalUnits

    /**
     * Bepaalt de volgende stap op grond van een verse meting.
     *
     * Is er nog een blok onderhanden, dan komt exact datzelfde blok terug: een
     * tweede meting mag nooit een tweede blok uitgeven, want dan zou werk dubbel
     * of juist niet gedaan worden.
     */
    public fun next(status: ThermalStatus, nowMs: Long): ChunkPlan {
        if (isDone) return ChunkPlan.Done

        // Een onderhanden blok komt onveranderd terug — behalve als het toestel
        // zichzelf uitzet. Zonder deze uitzondering was `Stopped` onbereikbaar
        // zolang er een blok in de lucht hing, en bleef de aanroeper doorwerken
        // terwijl het toestel uitging.
        outstanding?.let { onderhanden ->
            if (status < ThermalStatus.SHUTDOWN) {
                return ChunkPlan.Work(onderhanden, lastDecision?.status ?: status)
            }
            outstanding = null
        }

        val decision = governor.observe(status, nowMs)
        state = state.copy(thermal = governor.state)
        lastDecision = decision
        lastDecisionAtMs = nowMs

        if (!decision.resumable) {
            return ChunkPlan.Stopped(decision.reason ?: PauseReason.SHUTDOWN_IMMINENT, status)
        }
        if (decision.paused) {
            return ChunkPlan.Pause(decision.waitMs, decision.reason ?: PauseReason.OVERHEATED, status)
        }

        // Het laatste blok is meestal kleiner dan de blokgrootte; nooit voorbij het einde.
        val size = min(decision.chunkSize, remainingUnits)
        val chunk = WorkChunk(state.completedUnits, state.completedUnits + size)
        outstanding = chunk
        outstandingIssuedAtMs = nowMs
        return ChunkPlan.Work(chunk, status)
    }

    /** Meldt een volledig afgewerkt blok terug. */
    public fun complete(chunk: WorkChunk, nowMs: Long) {
        record(chunk, chunk.size, nowMs)
    }

    /**
     * Meldt een blok terug dat halverwege is afgebroken. De rest van het blok
     * blijft openstaan en komt gewoon weer terug bij de volgende [next].
     */
    public fun completePartially(chunk: WorkChunk, unitsDone: Int, nowMs: Long) {
        record(chunk, unitsDone, nowMs)
    }

    /**
     * Geeft het onderhanden blok terug zonder voortgang. Gebruik dit als de klus
     * onderbroken wordt: het blok wordt later opnieuw uitgegeven.
     */
    public fun abandon() {
        outstanding = null
    }

    /** Alles wat nodig is om deze klus later te hervatten. */
    public fun snapshot(): SchedulerState = state

    public fun report(nowMs: Long): ThermalReport {
        val decision = lastDecision
        val cooldownRemaining = if (decision != null && decision.paused) {
            max(0L, lastDecisionAtMs + decision.waitMs - nowMs)
        } else {
            0L
        }
        val done = isDone
        val estimate = estimateRemainingMs(cooldownRemaining)
        // Zonder verse meting — vlak na herstel uit een momentopname — komt de
        // toestand uit de bewaarde thermische staat. Anders meldt de eerste
        // `report()` na een herstart "Bezig", terwijl de eerstvolgende `next()`
        // meteen weer pauzeert: een stilstaande balk zonder uitleg.
        val bewaardGepauzeerd = decision == null && state.thermal.paused
        val paused = !done && (decision?.paused == true || bewaardGepauzeerd)
        val reason = when {
            done -> null
            decision != null -> decision.reason
            bewaardGepauzeerd -> PauseReason.OVERHEATED
            else -> null
        }
        val percent = (progress * 100).roundToInt()

        return ThermalReport(
            completedUnits = state.completedUnits,
            totalUnits = state.totalUnits,
            progress = progress,
            status = decision?.status ?: state.thermal.status,
            paused = paused,
            done = done,
            reason = reason,
            cooldownRemainingMs = if (done) 0L else cooldownRemaining,
            estimatedRemainingMs = estimate,
            message = buildMessage(percent, done, reason, cooldownRemaining, estimate),
        )
    }

    /**
     * Schatting uit de gemeten doorvoer. Die daalt vanzelf mee met de warmte,
     * omdat een heet toestel er langer over doet — precies wat je wilt tonen.
     */
    private fun estimateRemainingMs(cooldownRemainingMs: Long): Long? {
        if (isDone) return 0L
        if (state.measuredUnits == 0) return null
        val work = state.workedMs * remainingUnits / state.measuredUnits
        return work + cooldownRemainingMs
    }

    private fun record(chunk: WorkChunk, unitsDone: Int, nowMs: Long) {
        require(unitsDone in 0..chunk.size) {
            "unitsDone ($unitsDone) moet tussen 0 en de blokgrootte (${chunk.size}) liggen"
        }
        // Idempotent: een blok dat al verwerkt is, telt niet nog een keer mee.
        // Dat kan gebeuren na herstel uit een snapshot die verder was dan de aanroeper dacht.
        if (chunk.endExclusive <= state.completedUnits) {
            outstanding = null
            outstandingIssuedAtMs = null
            return
        }
        require(chunk.start == state.completedUnits) {
            "blok $chunk sluit niet aan op de voortgang (${state.completedUnits}) — zo raakt werk zoek"
        }

        // Alleen meten wat deze planner zelf heeft uitgegeven; zie
        // [outstandingIssuedAtMs]. Een blok van vóór een herstart telt wel mee
        // voor de voortgang, maar niet voor de doorvoerschatting.
        val issuedAtMs = outstandingIssuedAtMs
        val elapsed = if (issuedAtMs == null) 0L else max(0L, nowMs - issuedAtMs)
        val gemeten = if (issuedAtMs == null) 0 else unitsDone

        state = state.copy(
            completedUnits = state.completedUnits + unitsDone,
            workedMs = state.workedMs + elapsed,
            measuredUnits = state.measuredUnits + gemeten,
            chunksCompleted = state.chunksCompleted + if (unitsDone == chunk.size) 1 else 0,
        )
        outstanding = null
        outstandingIssuedAtMs = null
    }

    public companion object {
        /** Hervat een klus vanaf een eerder genomen [snapshot]. */
        public fun restore(
            state: SchedulerState,
            policy: ThermalPolicy = ThermalPolicy(),
        ): ChunkScheduler = ChunkScheduler(policy, state)
    }
}
