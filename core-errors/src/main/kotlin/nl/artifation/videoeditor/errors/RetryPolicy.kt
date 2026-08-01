package nl.artifation.videoeditor.errors

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * De bron van willekeur voor de jitter.
 *
 * Van buitenaf meegegeven en niet zelf aangemaakt: alleen zo zijn de wachttijden
 * in een test te voorspellen. In productie komt hier [Jitter.of] met een echte
 * [Random] in, in een test een vaste waarde.
 */
public fun interface Jitter {

    /** Een waarde in `[0, 1)`. Waarden erbuiten worden geknepen. */
    public fun next(): Double

    public companion object {
        /**
         * Precies het midden van het jitterbereik, dus een factor 1: de
         * berekende wachttijd komt er onveranderd uit.
         */
        public val NONE: Jitter = Jitter { 0.5 }

        public fun of(random: Random): Jitter = Jitter { random.nextDouble() }
    }
}

/**
 * Hoe lang een mislukte taak moet wachten voor hij het opnieuw mag proberen.
 *
 * Exponentiële backoff met een bovengrens en een maximum aantal pogingen, plus
 * jitter. Die jitter is geen franje: zonder jitter komt na een storing de hele
 * wachtrij op precies hetzelfde moment weer aankloppen en gaat de dienst voor de
 * tweede keer om.
 *
 * Alles hier is een pure functie van (fout, pogingnummer, jitter). Er wordt niet
 * geslapen en de klok wordt niet gelezen — het uitvoeren is aan de wachtrij.
 */
public data class RetryPolicy(
    /** Inclusief de eerste poging: 4 betekent één poging en hoogstens drie herhalingen. */
    val maxAttempts: Int = 4,
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 30_000L,
    val multiplier: Double = 2.0,
    /** 0.25 = de wachttijd schuift hoogstens een kwart omhoog of omlaag. */
    val jitterRatio: Double = 0.25,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts moet >= 1 zijn, was $maxAttempts" }
        require(baseDelayMs >= 0L) { "baseDelayMs moet >= 0 zijn, was $baseDelayMs" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs ($maxDelayMs) moet >= baseDelayMs ($baseDelayMs) zijn" }
        require(multiplier >= 1.0) { "multiplier moet >= 1 zijn, was $multiplier" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio moet in 0..1 liggen, was $jitterRatio" }
    }

    /**
     * @param attemptsSoFar aantal pogingen dat al is gedaan; 1 na de eerste mislukking.
     */
    public fun shouldRetry(error: EditorError, attemptsSoFar: Int): Boolean {
        require(attemptsSoFar >= 1) { "attemptsSoFar telt gedane pogingen en is dus >= 1, was $attemptsSoFar" }
        return error.retryable && attemptsSoFar < maxAttempts
    }

    /**
     * De wachttijd voor de volgende poging, of null als er geen volgende poging komt.
     *
     * @param attemptsSoFar aantal pogingen dat al is gedaan; 1 na de eerste mislukking.
     */
    public fun delayMsFor(
        error: EditorError,
        attemptsSoFar: Int,
        jitter: Jitter = Jitter.NONE,
    ): Long? {
        if (!shouldRetry(error, attemptsSoFar)) return null

        val exponential = baseDelayMs.toDouble() * multiplier.pow(attemptsSoFar - 1)
        val capped = min(exponential, maxDelayMs.toDouble())
        val factor = 1.0 - jitterRatio + 2.0 * jitterRatio * jitter.next().coerceIn(0.0, 1.0)
        val jittered = (capped * factor).roundToLong().coerceIn(0L, maxDelayMs)

        // Een dienst die zelf zegt hoe lang we weg moeten blijven, weet dat beter
        // dan onze formule. Die wachttijd mag daarom over de bovengrens heen.
        val demanded = error.retryAfterMs() ?: return jittered
        return maxOf(jittered, demanded)
    }

    /**
     * Alle wachttijden die nog volgen na de eerste mislukking.
     *
     * Leeg als de fout niet opnieuw geprobeerd mag worden. Handig om vooraf te
     * zien hoe lang een taak in het ergste geval blijft rondhangen.
     */
    public fun schedule(error: EditorError, jitter: Jitter = Jitter.NONE): List<Long> =
        (1 until maxAttempts).mapNotNull { delayMsFor(error, it, jitter) }
}
