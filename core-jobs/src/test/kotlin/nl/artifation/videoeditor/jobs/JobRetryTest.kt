package nl.artifation.videoeditor.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobRetryTest {

    private fun queueMet(job: Job): JobQueue = JobQueue(openGate()).apply { submit(job) }

    @Test
    fun `een herstelbare fout zet de taak terug in de wachtrij`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)

        val job = queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)

        assertEquals(JobState.Queued, job.state, "toestand was ${job.state}")
        assertEquals(1, job.attempts, "attempts was ${job.attempts}")
        assertNull(job.finishedAtUs, "een teruggelegde taak is niet afgerond: ${job.finishedAtUs}")
    }

    @Test
    fun `een blijvende fout is meteen definitief`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)

        // Een kapot bronbestand wordt bij poging twee niet ineens heel; het
        // oordeel komt van de aanroeper, deze module verzint dat niet zelf.
        val job = queue.fail("export", nowUs = 20L, reason = "bronbestand kapot", retryable = false)

        assertEquals(JobState.Failed, job.state, "toestand was ${job.state}")
        assertEquals(1, job.attempts, "een blijvende fout hoort geen pogingen op te maken: ${job.attempts}")
        assertEquals(20L, job.finishedAtUs, "finishedAtUs was ${job.finishedAtUs}")
    }

    @Test
    fun `de foutmelding blijft bewaard bij het terugleggen`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)

        val job = queue.fail("export", nowUs = 20L, reason = "504 van de dienst", retryable = true)

        assertEquals("504 van de dienst", job.lastError, "lastError was ${job.lastError}")
    }

    @Test
    fun `na het maximum aantal pogingen mislukt de taak definitief`() {
        val queue = queueMet(exportJob("export", maxAttempts = 3))

        repeat(3) { poging ->
            queue.startNext(nowUs = 10L + poging)
            queue.fail("export", nowUs = 15L + poging, reason = "netwerk weg", retryable = true)
        }

        val job = queue.job("export")!!
        assertEquals(JobState.Failed, job.state, "toestand was ${job.state} na ${job.attempts} pogingen")
        assertEquals(3, job.attempts, "attempts was ${job.attempts}")
    }

    @Test
    fun `een teruggelegde taak wordt gewoon opnieuw gekozen`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)

        val lease = queue.startNext(nowUs = 30L)

        assertEquals("export", lease?.job?.id, "gekozen: ${lease?.job?.id}")
        assertEquals(2, lease?.job?.attempts, "attempts was ${lease?.job?.attempts}")
    }

    @Test
    fun `een definitief mislukte taak wordt niet meer gekozen`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.fail("export", nowUs = 20L, reason = "bronbestand kapot", retryable = false)

        assertNull(queue.nextCandidate(), "een definitief mislukte taak hoort weg te blijven")
        assertNull(queue.startNext(nowUs = 30L), "er hoorde niets te starten")
    }

    @Test
    fun `zonder bewaarpunt begint een nieuwe poging bij nul`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.8f)

        val job = queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)

        // Het halve exportbestand is weg; 80% beloven dat opnieuw gedaan moet
        // worden laat de balk later stilstaan.
        assertEquals(0f, job.progress, "progress was ${job.progress}")
    }

    @Test
    fun `met een bewaarpunt blijft de voortgang staan`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.8f, resumeToken = "frame-4200")

        val job = queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)

        assertEquals(0.8f, job.progress, "progress was ${job.progress}")
        assertEquals("frame-4200", job.resumeToken, "bewaarpunt was ${job.resumeToken}")
    }

    @Test
    fun `een taak met een enkele poging krijgt geen tweede kans`() {
        val queue = queueMet(exportJob("export", maxAttempts = 1))
        queue.startNext(nowUs = 10L)

        val job = queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)

        assertEquals(JobState.Failed, job.state, "toestand was ${job.state}")
    }

    @Test
    fun `een geslaagde poging na een fout wist de fout niet uit het logboek`() {
        val queue = queueMet(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)
        queue.startNext(nowUs = 30L)

        val job = queue.succeed("export", nowUs = 40L)

        assertEquals(JobState.Succeeded, job.state, "toestand was ${job.state}")
        assertEquals("netwerk weg", job.lastError, "de eerdere fout hoort zichtbaar te blijven")
        assertEquals(2, job.attempts, "attempts was ${job.attempts}")
    }
}
