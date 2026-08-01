package nl.artifation.videoeditor.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** De pacing-ingang: de wachtrij vraagt vóór elke keuze of er gewerkt mag worden. */
class ThermalGateTest {

    @Test
    fun `een gesloten gate levert geen taak`() {
        val gate = FakeThermalGate()
        val queue = JobQueue(gate)
        queue.submit(exportJob("export"))
        gate.block("toestel te warm")

        assertNull(queue.startNext(nowUs = 10L), "bij een gesloten gate hoort er niets te starten")
    }

    @Test
    fun `een gesloten gate laat de wachtrij ongemoeid`() {
        val gate = FakeThermalGate()
        val queue = JobQueue(gate)
        queue.submit(exportJob("export", enqueuedAtUs = 0L))
        queue.submit(analysisJob("analyse", enqueuedAtUs = 1L))
        gate.block()

        queue.startNext(nowUs = 10L)

        // Niets geprobeerd, dus ook geen poging verbruikt: na afkoelen begint de
        // wachtrij precies waar hij was.
        val export = queue.job("export")!!
        assertEquals(JobState.Queued, export.state, "toestand was ${export.state}")
        assertEquals(0, export.attempts, "attempts was ${export.attempts}")

        gate.open()
        assertEquals("export", queue.startNext(nowUs = 20L)?.job?.id, "volgorde is behouden")
    }

    @Test
    fun `de blokgrootte van de gate komt mee in de lease`() {
        val gate = FakeThermalGate(WorkAllowance.of(chunkUs = 2_000_000L))
        val queue = JobQueue(gate)
        queue.submit(exportJob("export"))

        assertEquals(2_000_000L, queue.startNext(nowUs = 10L)?.chunkUs, "blokgrootte klopt niet")
    }

    @Test
    fun `de gate wordt geraadpleegd met het meegegeven tijdstip`() {
        val gate = FakeThermalGate()
        val queue = JobQueue(gate)
        queue.submit(exportJob("export"))

        queue.startNext(nowUs = 1_234L)

        assertEquals(listOf(1_234L), gate.calls, "geraadpleegd met ${gate.calls}")
    }

    @Test
    fun `de gate wordt niet lastiggevallen als er al genoeg draait`() {
        val gate = FakeThermalGate()
        val queue = JobQueue(gate, maxRunning = 1)
        queue.submit(exportJob("a", enqueuedAtUs = 0L))
        queue.submit(exportJob("b", enqueuedAtUs = 1L))
        queue.startNext(nowUs = 10L)
        gate.calls.clear()

        queue.startNext(nowUs = 11L)

        assertTrue(gate.calls.isEmpty(), "de gate werd toch geraadpleegd: ${gate.calls}")
    }

    @Test
    fun `een lege wachtrij vraagt wel toestemming maar start niets`() {
        val gate = FakeThermalGate()
        val queue = JobQueue(gate)

        assertNull(queue.startNext(nowUs = 7L), "er was niets te starten")
        assertEquals(listOf(7L), gate.calls, "geraadpleegd met ${gate.calls}")
    }

    @Test
    fun `een toegestaan werkblok van nul is onzin`() {
        assertFailsWith<IllegalArgumentException> { WorkAllowance(mayWork = true, chunkUs = 0L) }
        assertFailsWith<IllegalArgumentException> { WorkAllowance(mayWork = false, chunkUs = -1L) }
    }

    @Test
    fun `een geblokkeerde gate draagt een reden`() {
        val blocked = WorkAllowance.blocked("accu bijna leeg")

        assertEquals("accu bijna leeg", blocked.reason, "reden was ${blocked.reason}")
        assertEquals(0L, blocked.chunkUs, "chunkUs was ${blocked.chunkUs}")
    }
}
