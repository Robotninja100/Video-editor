package nl.artifation.videoeditor.pipeline

import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.jobs.Job
import nl.artifation.videoeditor.jobs.JobKind
import nl.artifation.videoeditor.jobs.JobQueue
import nl.artifation.videoeditor.jobs.JobState
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import nl.artifation.videoeditor.thermal.ThermalGovernor
import nl.artifation.videoeditor.thermal.ThermalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun analyseTaak(id: String, nowUs: Us = 0L) = Job(
    id = id,
    kind = JobKind.ClipAnalysis(sourceUri = "file:///$id.mp4", clipDurationUs = 60 * US_PER_SECOND),
    enqueuedAtUs = nowUs,
)

class ThermalWorkGateTest {

    @Test
    fun `een koel toestel krijgt een volledig werkblok`() {
        val gate = ThermalWorkGate(ThermalGovernor(), Thermometer(ThermalStatus.NONE))

        val toestemming = gate.allowance(nowUs = 0L)

        assertTrue(toestemming.mayWork, "koel toestel mag werken")
        assertTrue(toestemming.chunkUs > 0L, "blok was ${toestemming.chunkUs}us")
    }

    @Test
    fun `een warmer toestel krijgt een kleiner werkblok`() {
        val koel = ThermalWorkGate(ThermalGovernor(), Thermometer(ThermalStatus.NONE))
            .allowance(0L).chunkUs
        val warm = ThermalWorkGate(ThermalGovernor(), Thermometer(ThermalStatus.MODERATE))
            .allowance(0L).chunkUs

        assertTrue(warm < koel, "warm gaf $warm, koel gaf $koel")
        assertTrue(warm > 0L, "matig warm hoort nog te mogen werken")
    }

    @Test
    fun `een heet toestel wordt geblokkeerd met uitleg`() {
        val gate = ThermalWorkGate(ThermalGovernor(), Thermometer(ThermalStatus.CRITICAL))

        val toestemming = gate.allowance(nowUs = 0L)

        assertFalse(toestemming.mayWork, "kritiek toestel hoort niet te werken")
        assertNotNull(toestemming.reason, "een gebruiker die de balk ziet stilstaan wil weten waarom")
        assertTrue(
            toestemming.reason!!.isNotBlank(),
            "reden was leeg: '${toestemming.reason}'",
        )
    }

    @Test
    fun `een toestel dat zichzelf uitzet wordt ook geblokkeerd`() {
        val gate = ThermalWorkGate(ThermalGovernor(), Thermometer(ThermalStatus.SHUTDOWN))

        assertFalse(gate.allowance(nowUs = 0L).mayWork, "wachten heeft geen zin meer")
    }
}

/**
 * Het punt van deze module: `:core-jobs` definieert de poort maar implementeert
 * hem niet, en `:core-thermal` weet niets van taken. Deze tests bewijzen dat de
 * twee samen doen wat de bedoeling is — zonder de koppeling had de wachtrij
 * met een altijd-ja-poort gedraaid en was het toestel gewoon warm gelopen.
 */
class WachtrijOnderThermischeDrukTest {

    private val thermometer = Thermometer()
    private val gate = ThermalWorkGate(ThermalGovernor(), thermometer)
    private val queue = JobQueue(gate)

    @Test
    fun `hitte legt de wachtrij stil en afkoeling laat hem weer lopen`() {
        queue.submit(analyseTaak("a"))

        thermometer.status = ThermalStatus.CRITICAL
        assertNull(queue.startNext(nowUs = 0L), "een kritiek toestel hoort niets te starten")

        thermometer.status = ThermalStatus.NONE
        // Ruim voorbij de koeltijd, zodat de hysterese hervatten toestaat.
        val lease = queue.startNext(nowUs = 600 * US_PER_SECOND)

        assertNotNull(lease, "na afkoelen hoort het werk door te gaan")
        assertEquals("a", lease.job.id)
    }

    @Test
    fun `de taak blijft gewoon in de wachtrij staan tijdens de hitte`() {
        queue.submit(analyseTaak("a"))
        thermometer.status = ThermalStatus.CRITICAL

        queue.startNext(nowUs = 0L)

        assertEquals(
            JobState.Queued,
            queue.job("a")?.state,
            "hitte mag een taak pauzeren, niet laten verdwijnen",
        )
    }

    @Test
    fun `het werkblok uit de poort komt op de lease terecht`() {
        queue.submit(analyseTaak("a"))
        thermometer.status = ThermalStatus.LIGHT

        val lease = assertNotNull(queue.startNext(nowUs = 0L))

        assertEquals(
            gate.allowance(nowUs = 0L).chunkUs,
            lease.chunkUs,
            "de uitvoerder moet weten hoe groot zijn hap mag zijn",
        )
    }
}

/**
 * De tweede koppeling: een mislukte taak moet wachten voordat hij het opnieuw
 * probeert. Zonder `:core-errors` in de wachtrij beukte hij in een strakke lus
 * door bij een netwerkstoring.
 */
class WachtrijMetBackoffTest {

    private val queue = JobQueue(ThermalWorkGate(ThermalGovernor(), Thermometer()))

    @Test
    fun `een netwerkfout levert een wachttijd op voordat het opnieuw mag`() {
        queue.submit(analyseTaak("a"))
        queue.startNext(nowUs = 0L)

        queue.fail("a", nowUs = 0L, error = EditorError.NetworkUnavailable())

        assertNull(queue.startNext(nowUs = 1L), "direct opnieuw proberen is precies het probleem")
        val laterUs = assertNotNull(queue.nextReadyUs(nowUs = 1L), "er hoort een moment te zijn")
        assertNotNull(queue.startNext(nowUs = laterUs), "na de wachttijd mag het weer")
    }

    @Test
    fun `een blijvende fout wacht niet maar stopt`() {
        queue.submit(analyseTaak("a"))
        queue.startNext(nowUs = 0L)

        queue.fail("a", nowUs = 0L, error = EditorError.FileMissing("file:///a.mp4"))

        assertEquals(
            JobState.Failed,
            queue.job("a")?.state,
            "een verdwenen bestand komt niet terug door te wachten",
        )
    }

    @Test
    fun `de bewaarde fout draagt een tekst voor de gebruiker`() {
        queue.submit(analyseTaak("a"))
        queue.startNext(nowUs = 0L)
        queue.fail("a", nowUs = 0L, error = EditorError.FileMissing("file:///vakantie.mp4"))

        val fout = assertNotNull(queue.job("a")?.lastError)
        assertTrue(
            fout.userMessage.isNotBlank() && "Exception" !in fout.userMessage,
            "tekst: ${fout.userMessage}",
        )
    }
}
