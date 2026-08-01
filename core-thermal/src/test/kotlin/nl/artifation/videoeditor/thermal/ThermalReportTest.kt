package nl.artifation.videoeditor.thermal

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Een gebruiker die een voortgangsbalk ziet stilstaan zonder uitleg, denkt dat
 * de app hangt. Het rapport moet dus altijd zeggen wat er aan de hand is.
 */
class ThermalReportTest {

    /** Werkt één blok van 600 eenheden af in 6 seconden: 10ms per eenheid. */
    private fun halveKlus(): ChunkScheduler {
        val scheduler = ChunkScheduler(totalUnits = 1_200)
        val plan = scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work
        scheduler.complete(plan.chunk, 6_000L)
        return scheduler
    }

    @Test
    fun `een lopende klus meldt voortgang en een schatting`() {
        val scheduler = halveKlus()

        val rapport = scheduler.report(nowMs = 6_000L)

        assertEquals(50, rapport.percent, "was ${rapport.percent}")
        assertTrue(!rapport.paused, "rapport: $rapport")
        assertNull(rapport.reason, "was ${rapport.reason}")
        assertEquals(6_000L, rapport.estimatedRemainingMs, "was ${rapport.estimatedRemainingMs}")
        assertEquals("Bezig, 50% klaar. Nog ongeveer 6 seconden.", rapport.message, "was: ${rapport.message}")
    }

    @Test
    fun `zonder gemeten blokken is er nog geen schatting`() {
        val scheduler = ChunkScheduler(totalUnits = 1_200)

        val rapport = scheduler.report(nowMs = 0L)

        assertNull(rapport.estimatedRemainingMs, "was ${rapport.estimatedRemainingMs}")
        assertEquals("Bezig, 0% klaar.", rapport.message, "was: ${rapport.message}")
    }

    @Test
    fun `een pauze vertelt waarom en hoe lang nog`() {
        val scheduler = halveKlus()
        scheduler.next(ThermalStatus.SEVERE, 6_000L)

        val rapport = scheduler.report(nowMs = 6_000L)

        assertTrue(rapport.paused, "rapport: $rapport")
        assertEquals(PauseReason.OVERHEATED, rapport.reason, "was ${rapport.reason}")
        assertEquals(20_000L, rapport.cooldownRemainingMs, "was ${rapport.cooldownRemainingMs}")
        assertEquals(
            "Gepauzeerd: het toestel is te warm geworden. Nog ongeveer 20 seconden afkoelen. 50% klaar.",
            rapport.message,
            "was: ${rapport.message}",
        )
    }

    @Test
    fun `de resterende koeltijd loopt terug met de klok`() {
        val scheduler = halveKlus()
        scheduler.next(ThermalStatus.SEVERE, 6_000L)

        val na15s = scheduler.report(nowMs = 21_000L)

        assertEquals(5_000L, na15s.cooldownRemainingMs, "was ${na15s.cooldownRemainingMs}")
        assertTrue(na15s.message.contains("5 seconden"), "boodschap was: ${na15s.message}")
    }

    @Test
    fun `de schatting telt de koeltijd mee`() {
        val scheduler = halveKlus()
        scheduler.next(ThermalStatus.SEVERE, 6_000L)

        val rapport = scheduler.report(nowMs = 6_000L)

        // 6s resterend werk plus 20s afkoelen.
        assertEquals(26_000L, rapport.estimatedRemainingMs, "was ${rapport.estimatedRemainingMs}")
    }

    @Test
    fun `de schatting loopt op als het toestel trager wordt`() {
        val scheduler = ChunkScheduler(totalUnits = 1_800)
        val eerste = scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work
        scheduler.complete(eerste.chunk, 6_000L)
        val snelleSchatting = scheduler.report(6_000L).estimatedRemainingMs!!

        // Tweede blok duurt per eenheid vier keer zo lang: het toestel is teruggeschakeld.
        val tweede = scheduler.next(ThermalStatus.MODERATE, 6_000L) as ChunkPlan.Work
        val klaarOm = 6_000L + tweede.chunk.size * 40L
        scheduler.complete(tweede.chunk, klaarOm)
        val trageSchatting = scheduler.report(klaarOm).estimatedRemainingMs!!

        assertTrue(
            trageSchatting > snelleSchatting,
            "schatting moet oplopen: was $trageSchatting tegenover $snelleSchatting",
        )
    }

    @Test
    fun `een afgeronde klus meldt klaar en geen resterende tijd`() {
        val scheduler = ChunkScheduler(totalUnits = 600)
        val plan = scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work
        scheduler.complete(plan.chunk, 6_000L)

        val rapport = scheduler.report(nowMs = 6_000L)

        assertTrue(rapport.done, "rapport: $rapport")
        assertEquals(100, rapport.percent, "was ${rapport.percent}")
        assertEquals(0L, rapport.estimatedRemainingMs, "was ${rapport.estimatedRemainingMs}")
        assertEquals("Klaar.", rapport.message, "was: ${rapport.message}")
    }

    @Test
    fun `bij afsluiten legt het rapport uit dat er niet meer gewacht wordt`() {
        val scheduler = ChunkScheduler(totalUnits = 600)
        scheduler.next(ThermalStatus.SHUTDOWN, 0L)

        val rapport = scheduler.report(nowMs = 0L)

        assertEquals(PauseReason.SHUTDOWN_IMMINENT, rapport.reason, "was ${rapport.reason}")
        assertEquals(PauseReason.SHUTDOWN_IMMINENT.explanation, rapport.message, "was: ${rapport.message}")
    }
}

class DuurInWoordenTest {

    @Test
    fun `heel korte duren krijgen geen getal`() {
        assertEquals("een moment", formatDuration(0L), "was ${formatDuration(0L)}")
        assertEquals("een moment", formatDuration(999L), "was ${formatDuration(999L)}")
    }

    @Test
    fun `seconden staan in enkelvoud en meervoud`() {
        assertEquals("1 seconde", formatDuration(1_000L), "was ${formatDuration(1_000L)}")
        assertEquals("20 seconden", formatDuration(20_000L), "was ${formatDuration(20_000L)}")
    }

    @Test
    fun `vanaf een minuut wordt er in minuten geteld`() {
        assertEquals("1 minuut", formatDuration(60_000L), "was ${formatDuration(60_000L)}")
        assertEquals("2 minuten", formatDuration(120_000L), "was ${formatDuration(120_000L)}")
        assertEquals("5 minuten", formatDuration(300_000L), "was ${formatDuration(300_000L)}")
    }
}

/** De toestand moet een herstart van het proces overleven; vandaar serialisatie. */
class ToestandSerialisatieTest {

    private val json = Json

    @Test
    fun `een momentopname overleeft een rondje json`() {
        val scheduler = ChunkScheduler(totalUnits = 18_000)
        val plan = scheduler.next(ThermalStatus.MODERATE, 0L) as ChunkPlan.Work
        scheduler.complete(plan.chunk, 3_000L)

        val tekst = json.encodeToString(SchedulerState.serializer(), scheduler.snapshot())
        val terug = json.decodeFromString(SchedulerState.serializer(), tekst)

        assertEquals(scheduler.snapshot(), terug, "was $terug")
        assertEquals(180, terug.completedUnits, "was ${terug.completedUnits}")
    }

    @Test
    fun `de thermische toestand overleeft een rondje json`() {
        val governor = ThermalGovernor()
        governor.observe(ThermalStatus.SEVERE, 1_000L)

        val tekst = json.encodeToString(ThermalState.serializer(), governor.state)
        val terug = json.decodeFromString(ThermalState.serializer(), tekst)

        assertEquals(governor.state, terug, "was $terug")
        assertTrue(terug.paused, "de pauze hoort bewaard te blijven: $terug")
    }

    @Test
    fun `een beleid overleeft een rondje json`() {
        val beleid = ThermalPolicy(baseChunkSize = 900, cooldownMs = 15_000)

        val terug = json.decodeFromString(
            ThermalPolicy.serializer(),
            json.encodeToString(ThermalPolicy.serializer(), beleid),
        )

        assertEquals(beleid, terug, "was $terug")
    }
}
