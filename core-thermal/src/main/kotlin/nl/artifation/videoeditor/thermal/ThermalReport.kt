package nl.artifation.videoeditor.thermal

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Wat de aanroeper aan de gebruiker kan laten zien: hoever de klus is, of hij
 * stilstaat, waaróm hij stilstaat en hoe lang het naar verwachting nog duurt.
 *
 * Een stilstaande voortgangsbalk zonder uitleg leest als een vastgelopen app;
 * daarom bevat dit rapport altijd een [message] in gewone taal.
 *
 * @property estimatedRemainingMs schatting op basis van de gemeten doorvoer tot
 *   nu toe, inclusief de koeltijd die nu nog loopt. Null zolang er nog geen blok
 *   is afgerond en er dus niets te meten viel.
 */
public data class ThermalReport(
    val completedUnits: Int,
    val totalUnits: Int,
    val progress: Double,
    val status: ThermalStatus,
    val paused: Boolean,
    val done: Boolean,
    val reason: PauseReason?,
    val cooldownRemainingMs: Long,
    val estimatedRemainingMs: Long?,
    val message: String,
) {
    /** Voortgang als heel percentage, voor een voortgangsbalk of een notificatie. */
    public val percent: Int get() = (progress * 100).roundToInt()
}

/**
 * Duur in gewone taal. Bewust grof: een schatting op de seconde nauwkeurig
 * suggereert een precisie die er niet is, en flikkert bij elke herberekening.
 */
internal fun formatDuration(ms: Long): String = when {
    ms < 1_000L -> "een moment"
    ms < 60_000L -> {
        val seconds = (ms / 1_000.0).roundToLong()
        if (seconds == 1L) "1 seconde" else "$seconds seconden"
    }
    else -> {
        val minutes = (ms / 60_000.0).roundToLong().coerceAtLeast(1L)
        if (minutes == 1L) "1 minuut" else "$minutes minuten"
    }
}

internal fun buildMessage(
    percent: Int,
    done: Boolean,
    reason: PauseReason?,
    cooldownRemainingMs: Long,
    estimatedRemainingMs: Long?,
): String = when {
    done -> "Klaar."
    reason == PauseReason.SHUTDOWN_IMMINENT -> reason.explanation
    reason != null -> buildString {
        append(reason.explanation)
        append(" Nog ongeveer ")
        append(formatDuration(cooldownRemainingMs))
        append(" afkoelen. ")
        append(percent)
        append("% klaar.")
    }
    else -> buildString {
        append("Bezig, ")
        append(percent)
        append("% klaar.")
        if (estimatedRemainingMs != null) {
            append(" Nog ongeveer ")
            append(formatDuration(estimatedRemainingMs))
            append(".")
        }
    }
}
