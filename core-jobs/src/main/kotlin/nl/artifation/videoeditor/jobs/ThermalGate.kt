package nl.artifation.videoeditor.jobs

import nl.artifation.videoeditor.model.Us

/**
 * De rem op de wachtrij.
 *
 * Een export van tien minuten 4K laat een telefoon throttelen; dan is het beter
 * om te wachten of in kleinere blokken te werken dan door te beuken. De wachtrij
 * vraagt hier vóór elke keuze om toestemming, maar weet niets van temperatuur,
 * accu of energiebesparing — dat zit in `:core-thermal`. Hier staat alleen het
 * koppelvlak, zodat deze module pure JVM en zonder toestel testbaar blijft.
 */
public fun interface ThermalGate {

    /**
     * @param nowUs tijd komt als parameter binnen; deze module leest nooit de klok.
     */
    public fun allowance(nowUs: Us): WorkAllowance
}

/**
 * Het antwoord van de [ThermalGate]: mag er gewerkt worden, en zo ja hoe groot
 * mag het blok zijn dat de uitvoerder in één keer afhandelt.
 *
 * [chunkUs] is uitgedrukt in bronmateriaal, niet in wandkloktijd: "verwerk maximaal
 * vijf seconden video en kom dan terug". Zo kan de uitvoerder tussen twee blokken
 * de voortgang wegschrijven en de wachtrij opnieuw laten beslissen.
 */
public data class WorkAllowance(
    val mayWork: Boolean,
    val chunkUs: Us,
    /** Voor de UI: waarom er niet gewerkt mag worden. */
    val reason: String? = null,
) {
    init {
        require(chunkUs >= 0) { "chunkUs moet >= 0 zijn, was $chunkUs" }
        require(!mayWork || chunkUs > 0) { "een toegestaan werkblok moet groter dan nul zijn" }
    }

    public companion object {
        public fun blocked(reason: String): WorkAllowance =
            WorkAllowance(mayWork = false, chunkUs = 0L, reason = reason)

        public fun of(chunkUs: Us): WorkAllowance =
            WorkAllowance(mayWork = true, chunkUs = chunkUs)
    }
}
