package nl.artifation.videoeditor.thermal

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Waarom er gepauzeerd of gestopt is. De uitleg is voor de gebruiker bedoeld:
 * een voortgangsbalk die stilstaat zonder tekst leest als een vastgelopen app.
 */
public enum class PauseReason(public val explanation: String) {
    /** De status staat op of boven de pauzeerdrempel. */
    OVERHEATED("Gepauzeerd: het toestel is te warm geworden."),

    /** De status is al gezakt, maar de minimale koeltijd loopt nog. */
    COOLING_DOWN("Gepauzeerd: het toestel koelt nog af."),

    /** Het toestel zet zichzelf uit; wachten heeft geen zin meer. */
    SHUTDOWN_IMMINENT("Gestopt: het toestel schakelt zichzelf uit vanwege oververhitting."),
}

/**
 * Vertaalt een [ThermalStatus] naar gedrag: hoe groot mag een werkblok zijn,
 * wanneer wordt er gepauzeerd, en hoe lang koelen we af voordat we opnieuw meten.
 *
 * Het achterliggende meetgegeven: tien minuten aaneengesloten analyse maakt een
 * S24 Ultra zo heet dat alles ongeveer half zo snel wordt. Onbeheerd doorwerken
 * levert dan geen snelheidswinst meer op, alleen een hete telefoon en een lege
 * accu. Vandaar kleinere blokken bij oplopende warmte en pauzeren bij ernstige
 * belasting.
 *
 * [pauseAt] ligt strikt boven [resumeAt]: dat is de hysterese. Zonder dat gat
 * schommelt het systeem tussen pauzeren en hervatten zodra de status op een
 * grens balanceert, en dat is trager én slechter dan gewoon doorwerken.
 */
@Serializable
public data class ThermalPolicy(
    /** Blokgrootte bij een koel toestel, in werkeenheden (bijvoorbeeld frames). */
    val baseChunkSize: Int = 600,
    /** Ondergrens voor de blokgrootte; een blok van nul zou de scheduler laten vastlopen. */
    val minChunkSize: Int = 60,
    /** Factor op [baseChunkSize] bij [ThermalStatus.LIGHT]. */
    val lightFactor: Double = 0.6,
    /** Factor op [baseChunkSize] bij [ThermalStatus.MODERATE]. */
    val moderateFactor: Double = 0.3,
    /** Vanaf deze status wordt er gepauzeerd. */
    val pauseAt: ThermalStatus = ThermalStatus.SEVERE,
    /** Hervatten mag pas op of onder deze status. Strikt lager dan [pauseAt] — de hysterese. */
    val resumeAt: ThermalStatus = ThermalStatus.LIGHT,
    /** Vanaf deze status weegt afkoelen zwaarder dan [minWorkMs]; doorwerken is dan schadelijk. */
    val hardPauseAt: ThermalStatus = ThermalStatus.CRITICAL,
    /** Minimale koeltijd na een pauze. */
    val cooldownMs: Long = 20_000,
    /** Koeltijd vanaf [hardPauseAt]; daar helpt kort wachten niet. */
    val deepCooldownMs: Long = 60_000,
    /** Na hervatten mag er minstens zo lang niet opnieuw gepauzeerd worden — de tweede helft van de hysterese. */
    val minWorkMs: Long = 30_000,
    /** Wachttijd tot een nieuwe meting als de koeltijd om is maar het nog te warm is. Voorkomt rondpompen. */
    val recheckMs: Long = 5_000,
) {
    init {
        require(minChunkSize >= 1) { "minChunkSize moet >= 1 zijn, was $minChunkSize" }
        require(baseChunkSize >= minChunkSize) {
            "baseChunkSize ($baseChunkSize) moet >= minChunkSize ($minChunkSize) zijn"
        }
        require(lightFactor > 0.0 && lightFactor <= 1.0) { "lightFactor moet in (0, 1] liggen, was $lightFactor" }
        require(moderateFactor > 0.0 && moderateFactor <= lightFactor) {
            "moderateFactor ($moderateFactor) moet in (0, lightFactor] liggen"
        }
        require(resumeAt < pauseAt) {
            "resumeAt ($resumeAt) moet strikt onder pauseAt ($pauseAt) liggen, anders is er geen hysterese"
        }
        require(hardPauseAt >= pauseAt) { "hardPauseAt ($hardPauseAt) moet >= pauseAt ($pauseAt) zijn" }
        require(cooldownMs >= 0) { "cooldownMs moet >= 0 zijn, was $cooldownMs" }
        require(deepCooldownMs >= cooldownMs) {
            "deepCooldownMs ($deepCooldownMs) moet >= cooldownMs ($cooldownMs) zijn"
        }
        require(minWorkMs >= 0) { "minWorkMs moet >= 0 zijn, was $minWorkMs" }
        require(recheckMs > 0) { "recheckMs moet positief zijn, was $recheckMs — anders draait de aanroeper rond" }
    }

    /**
     * Blokgrootte bij deze status. Vanaf [pauseAt] is dat [minChunkSize]: die
     * waarde geldt alleen zolang de minimale werkduur nog loopt en er dus
     * gedwongen doorgewerkt wordt — dan wel met de kleinst mogelijke blokken.
     */
    public fun chunkSizeFor(status: ThermalStatus): Int = when {
        status >= pauseAt -> minChunkSize
        status >= ThermalStatus.MODERATE -> scaled(moderateFactor)
        status >= ThermalStatus.LIGHT -> scaled(lightFactor)
        else -> baseChunkSize
    }

    /** Koeltijd die hoort bij de status waarop gepauzeerd werd. */
    public fun cooldownFor(status: ThermalStatus): Long =
        if (status >= hardPauseAt) deepCooldownMs else cooldownMs

    public fun shouldPause(status: ThermalStatus): Boolean = status >= pauseAt

    public fun mayResume(status: ThermalStatus): Boolean = status <= resumeAt

    private fun scaled(factor: Double): Int =
        (baseChunkSize * factor).roundToInt().coerceIn(minChunkSize, baseChunkSize)
}

/**
 * Wat er op grond van één meting gedaan moet worden.
 *
 * @property chunkSize aantal werkeenheden voor het volgende blok; 0 bij pauze.
 * @property waitMs hoe lang wachten voordat er opnieuw gemeten wordt; 0 als er gewerkt mag worden.
 * @property resumable false bij [ThermalStatus.SHUTDOWN]: dan is later opnieuw kijken zinloos.
 */
public data class ThermalDecision(
    val status: ThermalStatus,
    val paused: Boolean,
    val chunkSize: Int,
    val waitMs: Long,
    val reason: PauseReason?,
    val resumable: Boolean = true,
)
