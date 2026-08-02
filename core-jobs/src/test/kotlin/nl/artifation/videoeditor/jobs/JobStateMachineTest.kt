package nl.artifation.videoeditor.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobStateMachineTest {

    @Test
    fun `een nieuwe taak staat in de wachtrij zonder pogingen`() {
        val job = analysisJob("a")

        assertEquals(JobState.Queued, job.state, "beginstand: $job")
        assertEquals(0, job.attempts, "attempts was ${job.attempts}")
        assertEquals(0f, job.progress, "progress was ${job.progress}")
        assertNull(job.startedAtUs, "startedAtUs was ${job.startedAtUs}")
    }

    @Test
    fun `een taak in de wachtrij kan gaan draaien`() {
        val job = analysisJob("a").withState(JobState.Running, nowUs = 500L)

        assertEquals(JobState.Running, job.state, "toestand was ${job.state}")
        assertEquals(1, job.attempts, "attempts was ${job.attempts}")
        assertEquals(500L, job.startedAtUs, "startedAtUs was ${job.startedAtUs}")
    }

    @Test
    fun `een draaiende taak kan slagen`() {
        val job = analysisJob("a").running().copy(progress = 0.4f, resumeToken = "blok-3")

        val done = job.withState(JobState.Succeeded, nowUs = 900L)

        assertEquals(JobState.Succeeded, done.state, "toestand was ${done.state}")
        assertEquals(1f, done.progress, "een geslaagde taak is per definitie af, was ${done.progress}")
        assertEquals(900L, done.finishedAtUs, "finishedAtUs was ${done.finishedAtUs}")
        assertNull(done.resumeToken, "bewaarpunt was ${done.resumeToken}")
    }

    @Test
    fun `een draaiende taak kan mislukken`() {
        val job = analysisJob("a").running().withState(JobState.Failed, nowUs = 900L)

        assertEquals(JobState.Failed, job.state, "toestand was ${job.state}")
        assertEquals(900L, job.finishedAtUs, "finishedAtUs was ${job.finishedAtUs}")
        assertTrue(job.isTerminal, "een mislukte taak hoort eindstation te zijn: $job")
    }

    @Test
    fun `een draaiende taak kan geannuleerd worden`() {
        val job = analysisJob("a").running().withState(JobState.Cancelled, nowUs = 900L)

        assertEquals(JobState.Cancelled, job.state, "toestand was ${job.state}")
        assertEquals(900L, job.finishedAtUs, "finishedAtUs was ${job.finishedAtUs}")
    }

    @Test
    fun `een draaiende taak kan gepauzeerd worden`() {
        val job = analysisJob("a").running().copy(progress = 0.6f)

        val paused = job.withState(JobState.Paused, nowUs = 900L)

        assertEquals(JobState.Paused, paused.state, "toestand was ${paused.state}")
        assertEquals(0.6f, paused.progress, "pauzeren mag geen werk weggooien, was ${paused.progress}")
        assertNull(paused.finishedAtUs, "gepauzeerd is niet afgerond: ${paused.finishedAtUs}")
    }

    @Test
    fun `een draaiende taak kan terug de wachtrij in`() {
        val job = analysisJob("a").running().withState(JobState.Queued, nowUs = 900L)

        assertEquals(JobState.Queued, job.state, "toestand was ${job.state}")
        assertEquals(1, job.attempts, "de gedane poging blijft geteld, was ${job.attempts}")
    }

    @Test
    fun `een taak in de wachtrij kan gepauzeerd en geannuleerd worden`() {
        val job = analysisJob("a")

        assertEquals(JobState.Paused, job.withState(JobState.Paused, 1L).state)
        assertEquals(JobState.Cancelled, job.withState(JobState.Cancelled, 1L).state)
    }

    @Test
    fun `een gepauzeerde taak gaat via de wachtrij terug aan het werk`() {
        val paused = analysisJob("a").running().withState(JobState.Paused, nowUs = 2L)

        assertEquals(JobState.Queued, paused.withState(JobState.Queued, 3L).state)

        // Direct naar Running zou de wachtrijvolgorde omzeilen.
        val fout = assertFailsWith<IllegalJobTransition> { paused.withState(JobState.Running, 3L) }
        assertEquals(JobState.Paused, fout.from, "van-toestand was ${fout.from}")
        assertEquals(JobState.Running, fout.to, "naar-toestand was ${fout.to}")
    }

    @Test
    fun `een geslaagde taak kan niet opnieuw starten`() {
        val done = analysisJob("a").running().withState(JobState.Succeeded, 2L)

        val fout = assertFailsWith<IllegalJobTransition> { done.withState(JobState.Running, 3L) }
        assertEquals("a", fout.jobId, "de fout moet de taak noemen: ${fout.message}")
    }

    @Test
    fun `een geannuleerde taak kan niet opnieuw starten`() {
        val cancelled = analysisJob("a").running().withState(JobState.Cancelled, 2L)

        assertFailsWith<IllegalJobTransition> { cancelled.withState(JobState.Running, 3L) }
        assertFailsWith<IllegalJobTransition> { cancelled.withState(JobState.Queued, 3L) }
    }

    @Test
    fun `een mislukte taak is eindstation`() {
        val failed = analysisJob("a").running().withState(JobState.Failed, 2L)

        // Opnieuw proberen loopt via Running -> Queued, nooit via Failed; anders
        // zou een eindtoestand niet eindig zijn.
        assertFailsWith<IllegalJobTransition> { failed.withState(JobState.Queued, 3L) }
        assertFailsWith<IllegalJobTransition> { failed.withState(JobState.Running, 3L) }
    }

    @Test
    fun `een taak kan niet naar zijn eigen toestand overgaan`() {
        val running = analysisJob("a").running()

        assertFailsWith<IllegalJobTransition> { running.withState(JobState.Running, 3L) }
        assertFailsWith<IllegalJobTransition> { analysisJob("b").withState(JobState.Queued, 3L) }
    }

    @Test
    fun `een taak in de wachtrij kan niet slagen zonder gedraaid te hebben`() {
        val queued = analysisJob("a")

        assertFailsWith<IllegalJobTransition> { queued.withState(JobState.Succeeded, 3L) }
        assertFailsWith<IllegalJobTransition> { queued.withState(JobState.Failed, 3L) }
    }

    @Test
    fun `een gepauzeerde taak kan niet slagen of mislukken`() {
        val paused = analysisJob("a").running().withState(JobState.Paused, 2L)

        assertFailsWith<IllegalJobTransition> { paused.withState(JobState.Succeeded, 3L) }
        assertFailsWith<IllegalJobTransition> { paused.withState(JobState.Failed, 3L) }
    }

    @Test
    fun `geen enkele eindtoestand heeft nog een uitgang`() {
        for (state in JobState.entries.filter { it.isTerminal }) {
            assertEquals(
                emptySet(),
                JobStateMachine.allowedFrom(state),
                "$state hoort geen uitgaande overgangen te hebben",
            )
        }
    }

    @Test
    fun `elke poging wordt geteld`() {
        var job = analysisJob("a")
        repeat(3) { poging ->
            job = job.withState(JobState.Running, nowUs = poging.toLong())
            assertEquals(poging + 1, job.attempts, "na poging ${poging + 1}: ${job.attempts}")
            if (poging < 2) job = job.withState(JobState.Queued, nowUs = poging.toLong())
        }
        assertTrue(!job.canRetry, "na drie van de drie pogingen mag er niets meer over zijn: $job")
    }

    @Test
    fun `een taak met onmogelijke waarden wordt niet gebouwd`() {
        assertFailsWith<IllegalArgumentException> { analysisJob("a").copy(progress = 1.5f) }
        assertFailsWith<IllegalArgumentException> { analysisJob("a").copy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { analysisJob("a").copy(id = " ".trim()) }
        assertFailsWith<IllegalArgumentException> { analysisJob("a").copy(estimatedWorkUs = 0L) }
    }
}
