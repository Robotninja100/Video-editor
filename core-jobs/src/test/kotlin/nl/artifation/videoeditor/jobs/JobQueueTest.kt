package nl.artifation.videoeditor.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobQueueTest {

    @Test
    fun `een lege wachtrij levert geen taak`() {
        val queue = JobQueue(openGate())

        assertNull(queue.nextCandidate(nowUs = 0L), "een lege wachtrij hoort niets te bieden")
        assertNull(queue.startNext(nowUs = 1L), "een lege wachtrij hoort niets te starten")
        assertEquals(0, queue.size, "size was ${queue.size}")
    }

    @Test
    fun `hogere prioriteit gaat voor`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("laag", enqueuedAtUs = 0L, priority = JobPriority.Low))
        queue.submit(analysisJob("normaal", enqueuedAtUs = 1L, priority = JobPriority.Normal))
        queue.submit(exportJob("hoog", enqueuedAtUs = 2L, priority = JobPriority.High))

        assertEquals("hoog", queue.nextCandidate(nowUs = 0L)?.id, "gekozen: ${queue.nextCandidate(nowUs = 0L)}")
        assertEquals(
            listOf("hoog", "normaal", "laag"),
            queue.all.map { it.id },
            "volgorde was ${queue.all.map { it.id }}",
        )
    }

    @Test
    fun `bij gelijke prioriteit wint wie het langst wacht`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("jong", enqueuedAtUs = 900L))
        queue.submit(analysisJob("oud", enqueuedAtUs = 100L))

        assertEquals("oud", queue.nextCandidate(nowUs = 0L)?.id, "gekozen: ${queue.nextCandidate(nowUs = 0L)}")
    }

    @Test
    fun `bij gelijke prioriteit en gelijk tijdstip telt de volgorde van indienen`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("eerst", enqueuedAtUs = 100L))
        queue.submit(analysisJob("daarna", enqueuedAtUs = 100L))

        assertEquals("eerst", queue.nextCandidate(nowUs = 0L)?.id, "gekozen: ${queue.nextCandidate(nowUs = 0L)}")
    }

    @Test
    fun `een gestarte taak wordt niet nog eens gekozen`() {
        val queue = JobQueue(openGate(), maxRunning = 2)
        queue.submit(analysisJob("a", enqueuedAtUs = 0L))
        queue.submit(analysisJob("b", enqueuedAtUs = 1L))

        assertEquals("a", queue.startNext(nowUs = 10L)?.job?.id)
        assertEquals("b", queue.startNext(nowUs = 11L)?.job?.id)
        assertNull(queue.startNext(nowUs = 12L), "er was niets meer te kiezen")
    }

    @Test
    fun `de wachtrij start niet meer taken tegelijk dan toegestaan`() {
        val queue = JobQueue(openGate(), maxRunning = 1)
        queue.submit(exportJob("export", enqueuedAtUs = 0L))
        queue.submit(analysisJob("analyse", enqueuedAtUs = 1L))

        assertEquals("export", queue.startNext(nowUs = 10L)?.job?.id)
        assertNull(queue.startNext(nowUs = 11L), "er draaide er al één")

        queue.succeed("export", nowUs = 20L)
        assertEquals("analyse", queue.startNext(nowUs = 21L)?.job?.id, "na afloop mag de volgende")
    }

    @Test
    fun `een gepauzeerde taak wordt nooit gekozen`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a", enqueuedAtUs = 0L))
        queue.pause("a", nowUs = 5L)

        assertNull(queue.nextCandidate(nowUs = 0L), "gepauzeerd hoort onzichtbaar te zijn voor de keuze")
        assertNull(queue.startNext(nowUs = 6L), "gepauzeerd hoort niet te starten")
    }

    @Test
    fun `een geannuleerde taak wordt nooit gekozen`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a", enqueuedAtUs = 0L))
        queue.submit(analysisJob("b", enqueuedAtUs = 1L))
        queue.cancel("a", nowUs = 5L)

        assertEquals("b", queue.startNext(nowUs = 6L)?.job?.id, "de geannuleerde taak werd toch gekozen")
    }

    @Test
    fun `hervatten geeft de taak zijn oude plek terug`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("oud", enqueuedAtUs = 100L))
        queue.submit(analysisJob("jong", enqueuedAtUs = 200L))
        queue.pause("oud", nowUs = 300L)
        assertEquals("jong", queue.nextCandidate(nowUs = 0L)?.id, "zolang 'oud' pauzeert is 'jong' aan de beurt")

        queue.resume("oud", nowUs = 400L)

        // Hervatten laat enqueuedAtUs met rust; anders raakt een taak die je even
        // pauzeert stelselmatig achteraan.
        assertEquals("oud", queue.nextCandidate(nowUs = 0L)?.id, "gekozen: ${queue.nextCandidate(nowUs = 0L)}")
    }

    @Test
    fun `alles pauzeren maakt de wachtrij leeg zonder taken kwijt te raken`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a", enqueuedAtUs = 0L))
        queue.submit(analysisJob("b", enqueuedAtUs = 1L))
        queue.submit(exportJob("c", enqueuedAtUs = 2L))
        queue.startNext(nowUs = 10L)

        val paused = queue.pauseAll(nowUs = 20L)

        assertEquals(3, paused.size, "gepauzeerd: ${paused.map { it.id }}")
        assertNull(queue.startNext(nowUs = 21L), "alles staat stil")
        assertEquals(3, queue.active().size, "actieve taken: ${queue.active().map { it.id }}")
    }

    @Test
    fun `alles hervatten maakt de wachtrij weer bruikbaar`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a", enqueuedAtUs = 0L))
        queue.submit(analysisJob("b", enqueuedAtUs = 1L))
        queue.pauseAll(nowUs = 10L)

        queue.resumeAll(nowUs = 20L)

        assertEquals("a", queue.startNext(nowUs = 21L)?.job?.id, "na hervatten is 'a' weer eerst")
    }

    @Test
    fun `alles pauzeren raakt afgeronde taken niet`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("klaar", enqueuedAtUs = 0L))
        queue.startNext(nowUs = 10L)
        queue.succeed("klaar", nowUs = 20L)

        queue.pauseAll(nowUs = 30L)

        assertEquals(JobState.Succeeded, queue.job("klaar")?.state, "was ${queue.job("klaar")?.state}")
    }

    @Test
    fun `dezelfde id twee keer indienen is een fout`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a"))

        assertFailsWith<IllegalArgumentException> { queue.submit(analysisJob("a")) }
    }

    @Test
    fun `hetzelfde werk twee keer aanbieden levert een taak op`() {
        val queue = JobQueue(openGate())
        val eerste = queue.submitDeduplicated(analysisJob("a", sourceUri = "content://clips/x"))

        val tweede = queue.submitDeduplicated(analysisJob("b", sourceUri = "content://clips/x"))

        assertEquals(eerste.id, tweede.id, "de tweede aanbieding hoorde de bestaande taak te zijn")
        assertEquals(1, queue.size, "wachtrij: ${queue.all.map { it.id }}")
    }

    @Test
    fun `hetzelfde werk mag opnieuw zodra de vorige taak klaar is`() {
        val queue = JobQueue(openGate())
        queue.submitDeduplicated(analysisJob("a", sourceUri = "content://clips/x"))
        queue.startNext(nowUs = 10L)
        queue.succeed("a", nowUs = 20L)

        queue.submitDeduplicated(analysisJob("b", sourceUri = "content://clips/x"))

        assertEquals(2, queue.size, "een afgeronde taak mag hernieuwde analyse niet blokkeren")
    }

    @Test
    fun `annuleren tijdens draaien weigert de late melding van de uitvoerder`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.5f)

        queue.cancel("export", nowUs = 20L)

        // De uitvoerder is nog bezig met zijn laatste blok en meldt daarna terug;
        // dat mag de annulering niet ongedaan maken.
        assertFailsWith<IllegalJobTransition> { queue.succeed("export", nowUs = 30L) }
        assertFailsWith<IllegalStateException> { queue.reportProgress("export", 0.9f) }
        assertEquals(JobState.Cancelled, queue.job("export")?.state, "was ${queue.job("export")?.state}")
    }

    @Test
    fun `voortgang melden kan alleen terwijl een taak draait`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a"))

        assertFailsWith<IllegalStateException> { queue.reportProgress("a", 0.3f) }
    }

    @Test
    fun `mislukken van een taak die niet draait wordt geweigerd`() {
        val queue = JobQueue(openGate())
        queue.submit(analysisJob("a"))

        assertFailsWith<IllegalJobTransition> {
            queue.fail("a", nowUs = 10L, error = NETWERK_WEG)
        }
    }

    @Test
    fun `een onbekende taak is een fout en geen stille no-op`() {
        val queue = JobQueue(openGate())

        assertFailsWith<IllegalArgumentException> { queue.succeed("bestaat-niet", nowUs = 1L) }
        assertFailsWith<IllegalArgumentException> { queue.cancel("bestaat-niet", nowUs = 1L) }
        assertFailsWith<IllegalArgumentException> { queue.reportProgress("bestaat-niet", 0.5f) }
    }

    @Test
    fun `afgeronde taken opruimen laat de actieve staan`() {
        val queue = JobQueue(openGate(), maxRunning = 3)
        queue.submit(analysisJob("geslaagd", enqueuedAtUs = 0L))
        queue.submit(analysisJob("geannuleerd", enqueuedAtUs = 1L))
        queue.submit(analysisJob("bezig", enqueuedAtUs = 2L))
        queue.submit(analysisJob("wacht", enqueuedAtUs = 3L))
        queue.startNext(nowUs = 10L)
        queue.succeed("geslaagd", nowUs = 11L)
        queue.cancel("geannuleerd", nowUs = 12L)
        queue.startNext(nowUs = 13L)

        val opgeruimd = queue.purgeTerminal()

        assertEquals(2, opgeruimd, "opgeruimd: $opgeruimd")
        assertEquals(
            listOf("bezig", "wacht"),
            queue.all.map { it.id },
            "overgebleven: ${queue.all.map { it.id }}",
        )
    }

    @Test
    fun `voortgang loopt nooit achteruit`() {
        val queue = JobQueue(openGate())
        queue.submit(exportJob("export"))
        queue.startNext(nowUs = 10L)
        queue.reportProgress("export", 0.7f)

        val terug = queue.reportProgress("export", 0.2f)

        assertEquals(0.7f, terug.progress, "voortgang was ${terug.progress}")
    }

    @Test
    fun `een lease draagt de taak en de toegestane blokgrootte`() {
        val gate = FakeThermalGate(WorkAllowance.of(chunkUs = 3_000_000L))
        val queue = JobQueue(gate)
        queue.submit(exportJob("export"))

        val lease = queue.startNext(nowUs = 10L)

        assertNotNull(lease, "er hoorde een taak uitgedeeld te worden")
        assertEquals("export", lease.job.id, "taak was ${lease.job.id}")
        assertEquals(3_000_000L, lease.chunkUs, "blokgrootte was ${lease.chunkUs}")
        assertEquals(JobState.Running, lease.job.state, "toestand was ${lease.job.state}")
        assertTrue(queue.job("export")!!.startedAtUs == 10L, "starttijd was ${queue.job("export")!!.startedAtUs}")
    }
}
