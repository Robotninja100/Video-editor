package nl.artifation.videoeditor.jobs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertNear(expected: Float, actual: Float, label: String) {
    assertTrue(abs(expected - actual) < 0.005f, "$label: verwacht ~$expected, was $actual")
}

class JobProgressTest {

    /** Klein werk: 8 seconden clip → 1 seconde geschat werk. */
    private fun kleineTaak(id: String, enqueuedAtUs: Long = 0L) =
        analysisJob(id, enqueuedAtUs = enqueuedAtUs, clipDurationUs = 8_000_000L)

    /** Groot werk: 600 seconden export → 300 seconden geschat werk. */
    private fun groteTaak(id: String, enqueuedAtUs: Long = 0L) = exportJob(
        id,
        enqueuedAtUs = enqueuedAtUs,
        // Gelijke prioriteit, zodat deze tests over gewicht gaan en niet over volgorde.
        priority = JobPriority.Normal,
        outputDurationUs = 600_000_000L,
    )

    @Test
    fun `de schatting volgt uit de soort taak`() {
        assertEquals(1_000_000L, kleineTaak("a").estimatedWorkUs, "analyse: ${kleineTaak("a").estimatedWorkUs}")
        assertEquals(300_000_000L, groteTaak("b").estimatedWorkUs, "export: ${groteTaak("b").estimatedWorkUs}")
    }

    @Test
    fun `een lege wachtrij is klaar`() {
        val voortgang = JobQueue(openGate()).progress()

        assertEquals(1f, voortgang.fraction, "fraction was ${voortgang.fraction}")
        assertTrue(voortgang.isIdle, "een lege wachtrij hoort stil te staan: $voortgang")
    }

    @Test
    fun `een korte taak die klaar is laat de balk niet verspringen`() {
        val queue = JobQueue(openGate(), maxRunning = 2)
        queue.submit(kleineTaak("analyse", enqueuedAtUs = 0L))
        queue.submit(groteTaak("export", enqueuedAtUs = 1L))
        queue.startNext(nowUs = 10L)
        queue.succeed("analyse", nowUs = 20L)

        val voortgang = queue.progress()

        // Ongewogen zou dit 50% zijn, terwijl er nog 300 van de 301 seconden werk
        // te gaan is.
        assertNear(0.0033f, voortgang.fraction, "gewogen voortgang")
        assertEquals(1_000_000L, voortgang.doneWorkUs, "doneWorkUs was ${voortgang.doneWorkUs}")
        assertEquals(301_000_000L, voortgang.totalWorkUs, "totalWorkUs was ${voortgang.totalWorkUs}")
    }

    @Test
    fun `voortgang van een draaiende taak telt gedeeltelijk mee`() {
        val queue = JobQueue(openGate())
        queue.submit(groteTaak("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.25f)

        assertNear(0.25f, queue.progress().fraction, "halverwege gemeten")
    }

    @Test
    fun `alles geslaagd is honderd procent`() {
        val queue = JobQueue(openGate())
        queue.submit(kleineTaak("a", enqueuedAtUs = 0L))
        queue.submit(groteTaak("b", enqueuedAtUs = 1L))
        repeat(2) {
            val lease = queue.startNext(nowUs = 10L + it)!!
            queue.succeed(lease.job.id, nowUs = 20L + it)
        }

        assertEquals(1f, queue.progress().fraction, "fraction was ${queue.progress().fraction}")
    }

    @Test
    fun `geannuleerde taken tellen niet mee`() {
        val queue = JobQueue(openGate(), maxRunning = 2)
        queue.submit(kleineTaak("analyse", enqueuedAtUs = 0L))
        queue.submit(groteTaak("export", enqueuedAtUs = 1L))
        queue.startNext(nowUs = 10L)
        queue.succeed("analyse", nowUs = 11L)

        queue.cancel("export", nowUs = 12L)

        // Dat werk gebeurt nooit meer; meetellen zou de balk voorgoed op 0,3%
        // laten staan terwijl er niets meer te doen is.
        val voortgang = queue.progress()
        assertEquals(1f, voortgang.fraction, "fraction was ${voortgang.fraction}")
        assertEquals(1, voortgang.cancelled, "cancelled was ${voortgang.cancelled}")
    }

    @Test
    fun `definitief mislukte taken tellen niet mee`() {
        val queue = JobQueue(openGate(), maxRunning = 2)
        queue.submit(kleineTaak("analyse", enqueuedAtUs = 0L))
        queue.submit(groteTaak("export", enqueuedAtUs = 1L))
        queue.startNext(nowUs = 10L)
        queue.succeed("analyse", nowUs = 11L)
        queue.startNext(nowUs = 12L)

        queue.fail("export", nowUs = 13L, reason = "bronbestand kapot", retryable = false)

        val voortgang = queue.progress()
        assertEquals(1f, voortgang.fraction, "fraction was ${voortgang.fraction}")
        assertEquals(1, voortgang.failed, "failed was ${voortgang.failed}")
    }

    @Test
    fun `een taak die opnieuw in de wachtrij komt telt weer volledig mee`() {
        val queue = JobQueue(openGate())
        queue.submit(groteTaak("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.5f)

        queue.fail("export", nowUs = 20L, reason = "netwerk weg", retryable = true)

        val voortgang = queue.progress()
        assertEquals(300_000_000L, voortgang.totalWorkUs, "totalWorkUs was ${voortgang.totalWorkUs}")
        assertEquals(0L, voortgang.doneWorkUs, "zonder bewaarpunt is al het werk weg: ${voortgang.doneWorkUs}")
    }

    @Test
    fun `werk dat er tijdens het draaien bij komt verlaagt de breuk maar niet het afgeronde werk`() {
        val queue = JobQueue(openGate())
        queue.submit(kleineTaak("analyse", enqueuedAtUs = 0L))
        queue.startNext(nowUs = 10L)
        queue.succeed("analyse", nowUs = 11L)
        val voor = queue.progress()

        queue.submit(groteTaak("export", enqueuedAtUs = 12L))
        val na = queue.progress()

        // Het plan wordt groter, dus de breuk zakt — daar is niets aan te doen.
        assertTrue(na.fraction < voor.fraction, "breuk ging van ${voor.fraction} naar ${na.fraction}")
        // Maar afgerond werk loopt nooit terug; daar hangt "x van y klaar" aan.
        assertEquals(voor.doneWorkUs, na.doneWorkUs, "doneWorkUs was ${na.doneWorkUs}")
        assertTrue(na.totalWorkUs > voor.totalWorkUs, "totalWorkUs was ${na.totalWorkUs}")
    }

    @Test
    fun `de tellers per toestand kloppen`() {
        val queue = JobQueue(openGate(), maxRunning = 5)
        queue.submit(kleineTaak("geslaagd", enqueuedAtUs = 0L))
        queue.submit(kleineTaak("bezig", enqueuedAtUs = 1L))
        queue.submit(kleineTaak("gepauzeerd", enqueuedAtUs = 2L))
        queue.submit(kleineTaak("geannuleerd", enqueuedAtUs = 3L))
        queue.submit(kleineTaak("wacht", enqueuedAtUs = 4L))
        queue.startNext(nowUs = 10L)
        queue.succeed("geslaagd", nowUs = 11L)
        queue.startNext(nowUs = 12L)
        queue.pause("gepauzeerd", nowUs = 13L)
        queue.cancel("geannuleerd", nowUs = 14L)

        val voortgang = queue.progress()

        assertEquals(1, voortgang.succeeded, "succeeded was ${voortgang.succeeded}")
        assertEquals(1, voortgang.running, "running was ${voortgang.running}")
        assertEquals(1, voortgang.paused, "paused was ${voortgang.paused}")
        assertEquals(1, voortgang.cancelled, "cancelled was ${voortgang.cancelled}")
        assertEquals(1, voortgang.queued, "queued was ${voortgang.queued}")
        assertEquals(3, voortgang.active, "active was ${voortgang.active}")
    }

    @Test
    fun `een gepauzeerde taak houdt zijn plek in de balk`() {
        val queue = JobQueue(openGate())
        queue.submit(groteTaak("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.4f)

        queue.pause("export", nowUs = 20L)

        // Pauzeren is geen annuleren: het werk staat nog op de rol.
        assertNear(0.4f, queue.progress().fraction, "voortgang na pauzeren")
        assertEquals(300_000_000L, queue.progress().totalWorkUs, "het werk telt nog mee")
    }

    @Test
    fun `voortgang buiten het bereik wordt afgekapt`() {
        val queue = JobQueue(openGate())
        queue.submit(groteTaak("export"))
        queue.startNext(nowUs = 10L)

        assertEquals(1f, queue.reportProgress("export", 3f).progress, "boven de één")
        assertEquals(1f, queue.reportProgress("export", -1f).progress, "onder nul, en nooit achteruit")
    }
}
