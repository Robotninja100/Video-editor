package nl.artifation.videoeditor.jobs

import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.errors.Jitter
import nl.artifation.videoeditor.errors.RemoteService
import nl.artifation.videoeditor.errors.RetryPolicy
import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Wachten na een fout.
 *
 * Zonder wachttijd gaat een mislukte taak meteen weer draaien; bij een
 * netwerkstoring beukt de app dan in een strakke lus door. De wachttijd komt uit
 * `RetryPolicy` en staat als tijdstip op de taak — de wachtrij slaapt nergens.
 */
class JobBackoffTest {

    private fun queueMet(
        job: Job,
        policy: RetryPolicy = RetryPolicy(),
        jitter: Jitter = Jitter.NONE,
    ): JobQueue = JobQueue(openGate(), policy = policy, jitter = jitter).apply { submit(job) }

    /** Start de taak op [nowUs] en laat hem meteen mislukken. */
    private fun JobQueue.mislukOp(nowUs: Us, error: EditorError = NETWERK_WEG): Job {
        startNext(nowUs)
        return fail("export", nowUs = nowUs, error = error)
    }

    @Test
    fun `de wachttijd loopt op tussen pogingen`() {
        val queue = queueMet(exportJob("export", maxAttempts = 4), policy = RetryPolicy(baseDelayMs = 500L))

        var nu = 0L
        val wachttijden = mutableListOf<Us>()
        repeat(3) {
            val job = queue.mislukOp(nu)
            wachttijden += job.notBeforeUs!! - nu
            nu = job.notBeforeUs!!
        }

        // Verdubbelen: de tweede storing kost meer geduld dan de eerste.
        assertEquals(
            listOf(500L * US_PER_MS, 1_000L * US_PER_MS, 2_000L * US_PER_MS),
            wachttijden,
            "wachttijden waren $wachttijden",
        )
    }

    @Test
    fun `de wachttijd loopt niet boven de bovengrens uit`() {
        val queue = queueMet(
            exportJob("export", maxAttempts = 5),
            policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 5_000L, multiplier = 10.0),
        )

        var nu = 0L
        var laatste = 0L
        repeat(3) {
            val job = queue.mislukOp(nu)
            laatste = job.notBeforeUs!! - nu
            nu = job.notBeforeUs!!
        }

        assertEquals(5_000L * US_PER_MS, laatste, "de derde wachttijd was $laatste")
    }

    @Test
    fun `een taak wordt niet gekozen voor zijn niet-voor-tijdstip`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L))

        val job = queue.mislukOp(nowUs = 0L)

        val netTeVroeg = job.notBeforeUs!! - 1L
        assertNull(queue.nextCandidate(netTeVroeg), "de taak wacht nog tot ${job.notBeforeUs}")
        assertNull(queue.startNext(netTeVroeg), "er hoorde niets te starten")
    }

    @Test
    fun `na zijn niet-voor-tijdstip wordt de taak gewoon gekozen`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L))
        val job = queue.mislukOp(nowUs = 0L)

        val lease = queue.startNext(job.notBeforeUs!!)

        assertEquals("export", lease?.job?.id, "gekozen: ${lease?.job?.id}")
        assertEquals(2, lease?.job?.attempts, "attempts was ${lease?.job?.attempts}")
        assertNull(lease?.job?.notBeforeUs, "een gestarte taak wacht nergens meer op")
    }

    @Test
    fun `een wachtende taak houdt de rest van de wachtrij niet tegen`() {
        val queue = JobQueue(openGate(), policy = RetryPolicy(baseDelayMs = 500L))
        queue.submit(exportJob("export", enqueuedAtUs = 0L))
        queue.submit(analysisJob("analyse", enqueuedAtUs = 1L))
        queue.mislukOp(nowUs = 0L)

        // De export heeft de hoogste prioriteit, maar zit in zijn backoff; dan
        // is er geen reden om het toestel te laten niksen.
        val lease = queue.startNext(nowUs = 1L)

        assertEquals("analyse", lease?.job?.id, "gekozen: ${lease?.job?.id}")
    }

    @Test
    fun `een niet-herhaalbare fout krijgt geen wachttijd maar gaat direct naar Failed`() {
        val queue = queueMet(exportJob("export"))

        val job = queue.mislukOp(nowUs = 0L, error = BRON_KAPOT)

        assertEquals(JobState.Failed, job.state, "toestand was ${job.state}")
        assertNull(job.notBeforeUs, "notBeforeUs was ${job.notBeforeUs}")
        assertEquals(1, job.attempts, "een blijvende fout maakt geen pogingen op: ${job.attempts}")
    }

    @Test
    fun `een Retry-After van de dienst wint van de eigen formule`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L, maxDelayMs = 30_000L))

        val job = queue.mislukOp(nowUs = 0L, error = teVaak(retryAfterMs = 90_000L))

        // Ook boven de eigen bovengrens: eerder terugkomen dan de dienst vraagt
        // levert alleen maar een tweede afwijzing op.
        assertEquals(90L * US_PER_SECOND, job.notBeforeUs, "notBeforeUs was ${job.notBeforeUs}")
    }

    @Test
    fun `zonder Retry-After telt de eigen formule gewoon`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L))

        val job = queue.mislukOp(nowUs = 0L, error = teVaak(retryAfterMs = null))

        assertEquals(500L * US_PER_MS, job.notBeforeUs, "notBeforeUs was ${job.notBeforeUs}")
    }

    @Test
    fun `de jitter komt van buiten en maakt de wachttijd voorspelbaar anders`() {
        val policy = RetryPolicy(baseDelayMs = 1_000L, jitterRatio = 0.25)

        val kort = queueMet(exportJob("export"), policy = policy, jitter = jitterVan(0.0))
            .mislukOp(nowUs = 0L).notBeforeUs
        val lang = queueMet(exportJob("export"), policy = policy, jitter = jitterVan(1.0))
            .mislukOp(nowUs = 0L).notBeforeUs

        // Een kwart omlaag en een kwart omhoog; zonder deze speling komt na een
        // storing de hele wachtrij op dezelfde milliseconde weer aankloppen.
        assertEquals(750L * US_PER_MS, kort, "de laagste jitter gaf $kort")
        assertEquals(1_250L * US_PER_MS, lang, "de hoogste jitter gaf $lang")
    }

    @Test
    fun `het pogingenbudget van de taak wint van dat van de policy`() {
        val ruimBeleid = RetryPolicy(maxAttempts = 10, baseDelayMs = 0L, maxDelayMs = 0L)
        val queue = queueMet(exportJob("export", maxAttempts = 2), policy = ruimBeleid)

        queue.mislukOp(nowUs = 0L)
        val job = queue.mislukOp(nowUs = 1L)

        assertEquals(JobState.Failed, job.state, "de taak mocht twee keer, toestand was ${job.state}")
    }

    @Test
    fun `een taak met een ruimer budget dan de policy mag toch door`() {
        val krapBeleid = RetryPolicy(maxAttempts = 2, baseDelayMs = 0L, maxDelayMs = 0L)
        val queue = queueMet(exportJob("export", maxAttempts = 4), policy = krapBeleid)

        queue.mislukOp(nowUs = 0L)
        queue.mislukOp(nowUs = 1L)
        val job = queue.mislukOp(nowUs = 2L)

        assertEquals(JobState.Queued, job.state, "toestand was ${job.state} na ${job.attempts} pogingen")
    }

    @Test
    fun `de wachtrij vertelt wanneer er weer iets te doen valt`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L))
        val job = queue.mislukOp(nowUs = 0L)

        // Zonder dit antwoord blijft een wachtrij met alleen wachtende taken
        // stilstaan tot er toevallig iets anders gebeurt.
        assertEquals(job.notBeforeUs, queue.nextReadyUs(nowUs = 1L), "was ${queue.nextReadyUs(nowUs = 1L)}")
        assertEquals(
            job.notBeforeUs,
            queue.nextReadyUs(job.notBeforeUs!!),
            "op het tijdstip zelf is er werk: ${queue.nextReadyUs(job.notBeforeUs!!)}",
        )
    }

    @Test
    fun `werk dat nu al kan wordt niet uitgesteld en een lege wachtrij plant niets`() {
        val queue = queueMet(exportJob("export"))

        assertEquals(0L, queue.nextReadyUs(nowUs = 0L), "een wachtende taak hoort nu al te kunnen")

        queue.mislukOp(nowUs = 0L, error = BRON_KAPOT)

        assertNull(queue.nextReadyUs(nowUs = 0L), "een definitief mislukte taak plant niets")
    }

    @Test
    fun `de bewaarde fout en de wachttijd overleven een rondje json`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L))
        val fout = EditorError.ServiceFailure(
            service = RemoteService.TRANSCRIPTION,
            statusCode = 503,
            detail = "upstream weg",
        )
        val job = queue.mislukOp(nowUs = 0L, error = fout)

        val hersteld = JobQueueJson.decode(JobQueueJson.encode(queue.snapshot())).jobs.single()

        assertEquals(fout, hersteld.lastError, "lastError was ${hersteld.lastError}")
        assertEquals(job.notBeforeUs, hersteld.notBeforeUs, "notBeforeUs was ${hersteld.notBeforeUs}")
        assertNotNull(hersteld.lastError?.userMessage, "de tekst voor de gebruiker hoort er nog te zijn")
    }

    @Test
    fun `een gepauzeerde taak verliest zijn wachttijd niet`() {
        val queue = queueMet(exportJob("export"), policy = RetryPolicy(baseDelayMs = 500L))
        val gewacht = queue.mislukOp(nowUs = 0L).notBeforeUs

        queue.pause("export", nowUs = 1L)
        val job = queue.resume("export", nowUs = 2L)

        // Anders is pauzeren en hervatten een manier om een rate limit te omzeilen.
        assertEquals(gewacht, job.notBeforeUs, "notBeforeUs was ${job.notBeforeUs}")
    }
}
