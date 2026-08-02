package nl.artifation.videoeditor.pipeline

import nl.artifation.videoeditor.jobs.ThermalGate
import nl.artifation.videoeditor.jobs.WorkAllowance
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import nl.artifation.videoeditor.thermal.ThermalGovernor
import nl.artifation.videoeditor.thermal.ThermalStatus

/**
 * Waar de temperatuur de wachtrij daadwerkelijk afremt.
 *
 * `:core-jobs` definieert [ThermalGate] maar implementeert hem niet, en
 * `:core-thermal` weet niets van taken. Zonder deze klasse hebben allebei hun
 * werk gedaan en gebeurt er in de praktijk niets: de wachtrij zou met een
 * altijd-ja-poort draaien en het toestel gewoon warm laten lopen.
 *
 * Er wordt hier bewust niets bedacht. De governor beslist (inclusief hysterese,
 * zodat het systeem niet klappert), deze klasse vertaalt alleen.
 */
public class ThermalWorkGate(
    private val governor: ThermalGovernor,
    private val readStatus: ThermalStatusSource,
    /**
     * Hoeveel bronmateriaal één blokeenheid van de governor waard is.
     *
     * De governor telt in abstracte eenheden (frames, samples, wat de klus ook
     * telt); de wachtrij vraagt om microseconden bronmateriaal. Eén eenheid per
     * seconde video is de standaard en past bij de blokgroottes van
     * `ThermalPolicy` — die gaan van 60 tot 600, dus van één tot tien minuten.
     */
    private val usPerChunkUnit: Us = US_PER_SECOND,
) : ThermalGate {

    override fun allowance(nowUs: Us): WorkAllowance {
        val decision = governor.observe(readStatus.current(), nowMs = nowUs / 1_000L)

        // Niet-hervatbaar betekent dat het toestel zichzelf uitzet; dan is
        // wachten zinloos en moet de wachtrij helemaal stoppen.
        if (decision.paused || !decision.resumable) {
            return WorkAllowance.blocked(
                decision.reason?.explanation ?: "Gepauzeerd: het toestel is te warm geworden.",
            )
        }

        val chunkUs = decision.chunkSize * usPerChunkUnit
        // Een blok van niets is geen blok: dan is doorwerken zinloos en hoort
        // de wachtrij te wachten in plaats van een lege lease uit te delen.
        if (chunkUs <= 0L) {
            return WorkAllowance.blocked("Gepauzeerd: er is nu geen ruimte om te werken.")
        }

        return WorkAllowance.of(chunkUs)
    }
}

/**
 * Waar de thermische toestand vandaan komt.
 *
 * Op Android is dit `PowerManager.getCurrentThermalStatus()`; in tests en op de
 * werkplek is het een vaste waarde. De abstractie staat hier en niet in
 * `:core-thermal`, zodat die module puur beleid blijft.
 */
public fun interface ThermalStatusSource {
    public fun current(): ThermalStatus
}
