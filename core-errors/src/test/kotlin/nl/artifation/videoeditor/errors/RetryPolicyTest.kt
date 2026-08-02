package nl.artifation.videoeditor.errors

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zonder jitter is de wachttijd een gewone som, en dus exact te controleren. */
private val STRAK = RetryPolicy(
    maxAttempts = 4,
    baseDelayMs = 1_000L,
    maxDelayMs = 8_000L,
    multiplier = 2.0,
    jitterRatio = 0.0,
)

private val NETWERK = EditorError.NetworkUnavailable(RemoteService.TRANSCRIPTION)
private val BESTAND = EditorError.FileMissing(SAMPLE_PATH)

class RetryPolicyTest {

    @Test
    fun `de wachttijd verdubbelt bij elke poging`() {
        assertEquals(1_000L, STRAK.delayMsFor(NETWERK, attemptsSoFar = 1))
        assertEquals(2_000L, STRAK.delayMsFor(NETWERK, attemptsSoFar = 2))
        assertEquals(4_000L, STRAK.delayMsFor(NETWERK, attemptsSoFar = 3))
    }

    @Test
    fun `na het maximum aantal pogingen komt er geen wachttijd meer`() {
        assertNull(STRAK.delayMsFor(NETWERK, attemptsSoFar = 4), "vier pogingen is vier pogingen")
        assertNull(STRAK.delayMsFor(NETWERK, attemptsSoFar = 9))
    }

    @Test
    fun `een fout zonder herstelkans wordt niet opnieuw geprobeerd`() {
        assertNull(STRAK.delayMsFor(BESTAND, attemptsSoFar = 1), "een verdwenen bestand komt niet terug")
        assertEquals(emptyList(), STRAK.schedule(BESTAND))
    }

    @Test
    fun `de wachttijd loopt tegen de bovengrens aan`() {
        val steil = RetryPolicy(
            maxAttempts = 5,
            baseDelayMs = 1_000L,
            maxDelayMs = 3_000L,
            multiplier = 10.0,
            jitterRatio = 0.0,
        )

        assertEquals(1_000L, steil.delayMsFor(NETWERK, attemptsSoFar = 1))
        assertEquals(3_000L, steil.delayMsFor(NETWERK, attemptsSoFar = 2))
        assertEquals(3_000L, steil.delayMsFor(NETWERK, attemptsSoFar = 3))
    }

    @Test
    fun `het schema toont alle wachttijden die nog volgen`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L), STRAK.schedule(NETWERK))
    }

    @Test
    fun `bij één toegestane poging volgt er geen herhaling`() {
        val eenmalig = STRAK.copy(maxAttempts = 1)

        assertEquals(emptyList(), eenmalig.schedule(NETWERK))
        assertNull(eenmalig.delayMsFor(NETWERK, attemptsSoFar = 1))
    }

    @Test
    fun `shouldRetry en delayMsFor zijn het altijd eens`() {
        for (attempt in 1..5) {
            val mag = STRAK.shouldRetry(NETWERK, attempt)
            assertEquals(mag, STRAK.delayMsFor(NETWERK, attempt) != null, "poging $attempt")
        }
    }

    @Test
    fun `een pogingnummer onder één is een fout in de aanroeper`() {
        assertFailsWith<IllegalArgumentException> { STRAK.delayMsFor(NETWERK, attemptsSoFar = 0) }
        assertFailsWith<IllegalArgumentException> { STRAK.shouldRetry(NETWERK, attemptsSoFar = -3) }
    }

    @Test
    fun `onzinnige instellingen worden meteen geweigerd`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(baseDelayMs = -1L) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(baseDelayMs = 10_000L, maxDelayMs = 5_000L) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(jitterRatio = 1.5) }
    }
}

class JitterTest {

    private val metJitter = STRAK.copy(jitterRatio = 0.25)

    @Test
    fun `de laagste worp geeft de kortste wachttijd`() {
        assertEquals(750L, metJitter.delayMsFor(NETWERK, attemptsSoFar = 1, jitter = { 0.0 }))
    }

    @Test
    fun `de hoogste worp geeft de langste wachttijd`() {
        assertEquals(1_250L, metJitter.delayMsFor(NETWERK, attemptsSoFar = 1, jitter = { 1.0 }))
    }

    @Test
    fun `het midden van het bereik verandert de wachttijd niet`() {
        assertEquals(1_000L, metJitter.delayMsFor(NETWERK, attemptsSoFar = 1, jitter = Jitter.NONE))
    }

    @Test
    fun `een worp buiten het bereik wordt geknepen`() {
        assertEquals(1_250L, metJitter.delayMsFor(NETWERK, attemptsSoFar = 1, jitter = { 42.0 }))
        assertEquals(750L, metJitter.delayMsFor(NETWERK, attemptsSoFar = 1, jitter = { -7.0 }))
    }

    @Test
    fun `jitter spreidt de wachttijden en blijft binnen de grenzen`() {
        // Vaste zaadwaarde: willekeurig genoeg om spreiding te zien, en toch
        // elke keer dezelfde reeks.
        val jitter = Jitter.of(Random(20260801))

        val wachttijden = List(200) { metJitter.delayMsFor(NETWERK, attemptsSoFar = 3, jitter = jitter)!! }

        assertTrue(
            wachttijden.toSet().size > 50,
            "te weinig spreiding: ${wachttijden.toSet().size} verschillende waarden",
        )
        assertTrue(
            wachttijden.all { it in 3_000L..5_000L },
            "buiten het jitterbereik: ${wachttijden.filter { it !in 3_000L..5_000L }}",
        )
    }

    @Test
    fun `jitter tilt de wachttijd nooit over de bovengrens`() {
        val ruim = RetryPolicy(
            maxAttempts = 6,
            baseDelayMs = 1_000L,
            maxDelayMs = 4_000L,
            multiplier = 3.0,
            jitterRatio = 1.0,
        )

        val wachttijd = ruim.delayMsFor(NETWERK, attemptsSoFar = 5, jitter = { 1.0 })

        assertEquals(4_000L, wachttijd, "de bovengrens geldt ook na de jitter")
    }

    @Test
    fun `zonder jitter geeft dezelfde poging altijd dezelfde wachttijd`() {
        val eerste = STRAK.schedule(NETWERK)
        val tweede = STRAK.schedule(NETWERK)

        assertEquals(eerste, tweede, "de berekening mag nergens van de klok of van toeval afhangen")
    }
}

class RetryAfterPolicyTest {

    @Test
    fun `een opgegeven wachttijd wint van de eigen berekening`() {
        val error = EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 60_000L)

        assertEquals(
            60_000L,
            STRAK.delayMsFor(error, attemptsSoFar = 1),
            "de dienst weet beter wanneer hij ons weer wil zien, ook boven de bovengrens",
        )
    }

    @Test
    fun `een kortere opgegeven wachttijd verandert niets`() {
        val error = EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 100L)

        assertEquals(1_000L, STRAK.delayMsFor(error, attemptsSoFar = 1))
    }

    @Test
    fun `een opgegeven wachttijd werkt ook door een omhulling heen`() {
        val error = EditorError.StageFailure(
            Stage.TRANSCRIPTION,
            EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 45_000L),
        )

        assertEquals(45_000L, STRAK.delayMsFor(error, attemptsSoFar = 2))
    }
}
