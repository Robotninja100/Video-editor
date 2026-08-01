package nl.artifation.videoeditor.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Analyse en export duren minuten; de wachtrij moet een procesdood overleven. */
class JobPersistenceTest {

    private fun rondje(queue: JobQueue): QueueSnapshot =
        JobQueueJson.decode(JobQueueJson.encode(queue.snapshot()))

    @Test
    fun `een wachtrij overleeft een rondje json`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a", enqueuedAtUs = 100L))
        queue.submit(exportJob("b", enqueuedAtUs = 200L))
        queue.startNext(nowUs = 300L)
        queue.reportProgress("b", 0.35f, resumeToken = "frame-900")

        val hersteld = rondje(queue)

        assertEquals(queue.snapshot(), hersteld, "de wachtrij kwam anders terug: $hersteld")
    }

    @Test
    fun `elke taaksoort overleeft een rondje json`() {
        val soorten = listOf(
            JobKind.ClipAnalysis(sourceUri = "content://clip/1", clipDurationUs = 90_000_000L),
            JobKind.Transcription(sourceUri = "content://clip/1", clipDurationUs = 90_000_000L, language = "nl"),
            JobKind.Segmentation(sourceUri = "content://clip/1", frameCount = 240),
            JobKind.Export(projectId = "p1", outputUri = "content://out/1", outputDurationUs = 60_000_000L),
        )
        val queue = JobQueue(openGate())
        soorten.forEachIndexed { index, kind ->
            queue.submit(Job(id = "taak-$index", kind = kind, enqueuedAtUs = index.toLong()))
        }

        val hersteld = rondje(queue)

        assertEquals(soorten, hersteld.jobs.map { it.kind }, "soorten kwamen anders terug: ${hersteld.jobs}")
    }

    @Test
    fun `het bewaarpunt overleeft de herstart`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.6f, resumeToken = "frame-4200")

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        val job = na.job("export")!!
        assertEquals("frame-4200", job.resumeToken, "bewaarpunt was ${job.resumeToken}")
        assertEquals(0.6f, job.progress, "met bewaarpunt hoeft het werk niet over: ${job.progress}")
    }

    @Test
    fun `een taak die stond te draaien komt terug in de wachtrij`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export"))
        queue.startNext(nowUs = 10L)

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        val job = na.job("export")!!
        assertEquals(JobState.Queued, job.state, "toestand was ${job.state}")
        assertNotNull(job.lastError, "de onderbreking hoort zichtbaar te zijn")
        assertEquals("export", na.nextCandidate()?.id, "de taak hoort weer gekozen te kunnen worden")
    }

    @Test
    fun `de verbruikte poging blijft na een procesdood geteld`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export", maxAttempts = 3))
        queue.startNext(nowUs = 10L)

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        // Een taak die het proces sloopt mag niet eindeloos herstarten.
        assertEquals(1, na.job("export")!!.attempts, "attempts was ${na.job("export")!!.attempts}")
    }

    @Test
    fun `een taak die door zijn pogingen heen is mislukt na een procesdood definitief`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export", maxAttempts = 1))
        queue.startNext(nowUs = 10L)

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        val job = na.job("export")!!
        assertEquals(JobState.Failed, job.state, "toestand was ${job.state}")
        assertEquals(999L, job.finishedAtUs, "finishedAtUs was ${job.finishedAtUs}")
        assertNull(na.nextCandidate(), "er hoorde niets meer te kiezen te zijn")
    }

    @Test
    fun `zonder bewaarpunt begint een onderbroken taak overnieuw`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.7f)

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        assertEquals(0f, na.job("export")!!.progress, "progress was ${na.job("export")!!.progress}")
    }

    @Test
    fun `taken die niet draaiden blijven ongemoeid bij het laden`() {
        val queue = JobQueue(openGate(), maxRunning = 4)
        queue.submit(analysisJob("geslaagd", enqueuedAtUs = 0L))
        queue.submit(analysisJob("gepauzeerd", enqueuedAtUs = 1L))
        queue.submit(analysisJob("geannuleerd", enqueuedAtUs = 2L))
        queue.submit(analysisJob("wacht", enqueuedAtUs = 3L))
        queue.startNext(nowUs = 10L)
        queue.succeed("geslaagd", nowUs = 11L)
        queue.pause("gepauzeerd", nowUs = 12L)
        queue.cancel("geannuleerd", nowUs = 13L)

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        assertEquals(JobState.Succeeded, na.job("geslaagd")?.state, "was ${na.job("geslaagd")?.state}")
        assertEquals(JobState.Paused, na.job("gepauzeerd")?.state, "was ${na.job("gepauzeerd")?.state}")
        assertEquals(JobState.Cancelled, na.job("geannuleerd")?.state, "was ${na.job("geannuleerd")?.state}")
        assertEquals(JobState.Queued, na.job("wacht")?.state, "was ${na.job("wacht")?.state}")
    }

    @Test
    fun `de volgorde blijft na een herstart hetzelfde`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("eerst", enqueuedAtUs = 100L))
        queue.submit(analysisJob("daarna", enqueuedAtUs = 100L))
        queue.submit(exportJob("voorrang", enqueuedAtUs = 100L))

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        assertEquals(
            listOf("voorrang", "eerst", "daarna"),
            na.all.map { it.id },
            "volgorde was ${na.all.map { it.id }}",
        )
    }

    @Test
    fun `een lege wachtrij overleeft een rondje json`() {
        val na = JobQueue.restore(rondje(JobQueue(openGate())), nowUs = 999L, gate = openGate())

        assertEquals(0, na.size, "size was ${na.size}")
    }

    @Test
    fun `het opgeslagen bestand draagt een versie`() {
        val json = JobQueueJson.encode(JobQueue(openGate()).snapshot())

        assertTrue(json.contains("\"version\": 1"), "json was: $json")
    }

    @Test
    fun `onbekende velden uit een nieuwere versie blokkeren het laden niet`() {
        val json = """
            {
              "jobs": [],
              "version": 1,
              "toekomstigVeld": "iets nieuws"
            }
        """.trimIndent()

        assertEquals(0, JobQueueJson.decode(json).jobs.size, "onbekend veld hoorde genegeerd te worden")
    }

    @Test
    fun `de schatting wordt meegeschreven en niet opnieuw berekend bij het laden`() {
        val job = analysisJob("a").copy(estimatedWorkUs = 123_456L)
        val queue = JobQueue(openGate()).apply { submit(job) }

        val na = JobQueue.restore(rondje(queue), nowUs = 999L, gate = openGate())

        assertEquals(123_456L, na.job("a")!!.estimatedWorkUs, "was ${na.job("a")!!.estimatedWorkUs}")
    }
}
