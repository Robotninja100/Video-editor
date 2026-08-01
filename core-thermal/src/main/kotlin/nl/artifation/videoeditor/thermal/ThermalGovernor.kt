package nl.artifation.videoeditor.thermal

import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * De toestand van de hysterese tussen twee metingen door. Apart en
 * serialiseerbaar, zodat een klus die het proces niet overleeft na herstart
 * niet meteen weer in een pauze-hervat-slingering belandt.
 *
 * @property changedAtMs tijdstip van de laatste overgang werken<->pauzeren; null vóór de eerste overgang.
 * @property cooldownUntilMs tijdstip waarop de koeltijd om is; alleen betekenisvol tijdens een pauze.
 * @property transitions aantal overgangen tot nu toe. Blijft laag als de hysterese zijn werk doet.
 */
@Serializable
public data class ThermalState(
    val paused: Boolean = false,
    val status: ThermalStatus = ThermalStatus.NONE,
    val changedAtMs: Long? = null,
    val cooldownUntilMs: Long = 0L,
    val transitions: Int = 0,
) {
    init {
        require(transitions >= 0) { "transitions moet >= 0 zijn, was $transitions" }
    }
}

/**
 * Beslist per meting of er gewerkt of gepauzeerd wordt, mét hysterese.
 *
 * De hysterese zit in twee dingen tegelijk:
 *  1. pauzeren gebeurt op een hógere drempel ([ThermalPolicy.pauseAt]) dan
 *     hervatten ([ThermalPolicy.resumeAt]);
 *  2. na hervatten geldt [ThermalPolicy.minWorkMs] waarin niet opnieuw
 *     gepauzeerd mag worden.
 *
 * Alleen vanaf [ThermalPolicy.hardPauseAt] wordt punt 2 genegeerd: op kritiek
 * doorwerken omdat de klok het toestaat, is precies het gedrag dat je niet wilt.
 *
 * Geen threads, geen timers, geen systeemklok: de tijd komt binnen als parameter.
 */
public class ThermalGovernor(
    public val policy: ThermalPolicy = ThermalPolicy(),
    initial: ThermalState = ThermalState(),
) {
    /** De huidige toestand; te bewaren en later mee te herstarten. */
    public var state: ThermalState = initial
        private set

    public val transitions: Int get() = state.transitions

    public val paused: Boolean get() = state.paused

    /** Verwerkt één meting en geeft terug wat de aanroeper moet doen. */
    public fun observe(status: ThermalStatus, nowMs: Long): ThermalDecision {
        val previous = state
        require(previous.changedAtMs == null || nowMs >= previous.changedAtMs) {
            "de klok loopt terug: nowMs=$nowMs, laatste overgang=${previous.changedAtMs}"
        }

        // SHUTDOWN is geen pauze maar een einde: het toestel gaat uit, koelen helpt niet meer.
        if (status == ThermalStatus.SHUTDOWN) return halt(previous, nowMs)

        return if (previous.paused) {
            resumeOrKeepCooling(previous, status, nowMs)
        } else {
            keepWorkingOrPause(previous, status, nowMs)
        }
    }

    private fun halt(previous: ThermalState, nowMs: Long): ThermalDecision {
        state = if (previous.paused) {
            previous.copy(status = ThermalStatus.SHUTDOWN)
        } else {
            previous.copy(
                paused = true,
                status = ThermalStatus.SHUTDOWN,
                changedAtMs = nowMs,
                cooldownUntilMs = nowMs,
                transitions = previous.transitions + 1,
            )
        }
        return ThermalDecision(
            status = ThermalStatus.SHUTDOWN,
            paused = true,
            chunkSize = 0,
            waitMs = 0L,
            reason = PauseReason.SHUTDOWN_IMMINENT,
            resumable = false,
        )
    }

    private fun keepWorkingOrPause(
        previous: ThermalState,
        status: ThermalStatus,
        nowMs: Long,
    ): ThermalDecision {
        // Vóór de eerste overgang is er geen beschermde werkperiode: een klus die
        // al heet begint, moet meteen kunnen pauzeren.
        val workedLongEnough = previous.changedAtMs == null ||
            nowMs - previous.changedAtMs >= policy.minWorkMs
        val forced = status >= policy.hardPauseAt

        if (policy.shouldPause(status) && (forced || workedLongEnough)) {
            val until = nowMs + policy.cooldownFor(status)
            state = previous.copy(
                paused = true,
                status = status,
                changedAtMs = nowMs,
                cooldownUntilMs = until,
                transitions = previous.transitions + 1,
            )
            return ThermalDecision(
                status = status,
                paused = true,
                chunkSize = 0,
                waitMs = until - nowMs,
                reason = PauseReason.OVERHEATED,
            )
        }

        state = previous.copy(status = status)
        return working(status)
    }

    private fun resumeOrKeepCooling(
        previous: ThermalState,
        status: ThermalStatus,
        nowMs: Long,
    ): ThermalDecision {
        // Loopt het tijdens de pauze alsnog op tot kritiek, dan wordt de koeltijd
        // verlengd zonder dat dat als overgang telt — het blijft dezelfde pauze.
        val cooldownUntil = if (status >= policy.hardPauseAt) {
            max(previous.cooldownUntilMs, nowMs + policy.deepCooldownMs)
        } else {
            previous.cooldownUntilMs
        }

        if (nowMs >= cooldownUntil && policy.mayResume(status)) {
            state = previous.copy(
                paused = false,
                status = status,
                changedAtMs = nowMs,
                cooldownUntilMs = 0L,
                transitions = previous.transitions + 1,
            )
            return working(status)
        }

        state = previous.copy(status = status, cooldownUntilMs = cooldownUntil)
        val reason = if (policy.mayResume(status)) PauseReason.COOLING_DOWN else PauseReason.OVERHEATED
        // Nooit 0 wachten: anders blijft de aanroeper metingen rondpompen zolang het warm is.
        val waitMs = max(cooldownUntil - nowMs, policy.recheckMs)
        return ThermalDecision(
            status = status,
            paused = true,
            chunkSize = 0,
            waitMs = waitMs,
            reason = reason,
        )
    }

    private fun working(status: ThermalStatus): ThermalDecision = ThermalDecision(
        status = status,
        paused = false,
        chunkSize = policy.chunkSizeFor(status),
        waitMs = 0L,
        reason = null,
    )
}
