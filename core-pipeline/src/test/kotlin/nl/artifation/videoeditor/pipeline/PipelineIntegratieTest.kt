package nl.artifation.videoeditor.pipeline

import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.jobs.JobKind
import nl.artifation.videoeditor.jobs.JobQueue
import nl.artifation.videoeditor.jobs.JobState
import nl.artifation.videoeditor.library.AnalysisKind
import nl.artifation.videoeditor.library.InMemorySidecarStorage
import nl.artifation.videoeditor.library.MediaCatalog
import nl.artifation.videoeditor.library.SidecarIndex
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.thermal.ThermalGovernor
import nl.artifation.videoeditor.thermal.ThermalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wat er gebeurt tussen "de gebruiker kiest een video" en "er staat werk klaar".
 *
 * Deze tests raken vier modules tegelijk en bewijzen dat de koppelingen kloppen —
 * niet dat de modules zelf werken, dat doen hun eigen tests.
 */
class ImportNaarWerkTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)

    @Test
    fun `een verse import levert analysewerk op`() {
        val taken = AnalysisPlanner.plan(asset("a"), index, nowUs = 0L)

        assertTrue(taken.isNotEmpty(), "een clip zonder sidecars heeft werk nodig")
        assertTrue(
            taken.any { it.kind is JobKind.ClipAnalysis },
            "stiltes en scenes ontbreken: ${taken.map { it.kind }}",
        )
        assertTrue(
            taken.any { it.kind is JobKind.Transcription },
            "transcript ontbreekt: ${taken.map { it.kind }}",
        )
    }

    @Test
    fun `een clip die al geanalyseerd is levert geen werk op`() {
        val a = asset("a")
        for (kind in AnalysisKind.entries) index.write(a, kind)

        assertEquals(emptyList(), AnalysisPlanner.plan(a, index, nowUs = 0L))
    }

    @Test
    fun `stiltes en scenes worden in een taak gepland, niet in twee`() {
        val a = asset("a")
        index.write(a, AnalysisKind.TRANSCRIPT)

        val taken = AnalysisPlanner.plan(a, index, nowUs = 0L)

        assertEquals(1, taken.size, "één doorloop over het materiaal, niet twee: $taken")
        assertTrue(taken.single().kind is JobKind.ClipAnalysis)
    }

    /**
     * De kern van de sidecar-architectuur: een beter model draaien mag de
     * montage niet raken, maar moet het werk wél opnieuw inplannen.
     */
    @Test
    fun `gewijzigd bronmateriaal plant de analyse opnieuw in`() {
        val origineel = asset("a", revision = 1L)
        for (kind in AnalysisKind.entries) index.write(origineel, kind)
        assertEquals(emptyList(), AnalysisPlanner.plan(origineel, index, nowUs = 0L))

        val gewijzigd = origineel.copy(sourceRevision = 2L)

        assertTrue(
            AnalysisPlanner.plan(gewijzigd, index, nowUs = 0L).isNotEmpty(),
            "een gewijzigde bron maakt de oude analyse ongeldig",
        )
    }

    @Test
    fun `segmentatie wordt alleen op verzoek gepland`() {
        val a = asset("a")

        val zonder = AnalysisPlanner.plan(a, index, nowUs = 0L, includeSegmentation = false)
        val met = AnalysisPlanner.plan(a, index, nowUs = 0L, includeSegmentation = true)

        assertFalse(
            zonder.any { it.kind is JobKind.Segmentation },
            "segmentatie kost geld per frame en hoort niet bij elke import",
        )
        assertTrue(met.any { it.kind is JobKind.Segmentation })
    }

    @Test
    fun `een lagere trackingframerate levert minder frames op`() {
        val a = asset("a", seconds = 60)

        fun framesBij(fps: Int) = AnalysisPlanner
            .plan(a, index, nowUs = 0L, trackingFps = fps, includeSegmentation = true)
            .map { it.kind }
            .filterIsInstance<JobKind.Segmentation>()
            .single()
            .frameCount

        assertEquals(600, framesBij(10), "60 s op 10 fps")
        assertEquals(1_800, framesBij(30), "60 s op 30 fps")
    }

    @Test
    fun `een hele bibliotheek wordt in een keer gepland`() {
        val catalogus = MediaCatalog().add(asset("a")).add(asset("b"))

        val taken = AnalysisPlanner.planAll(catalogus.assets, index, nowUs = 0L)

        assertEquals(
            setOf("a", "b"),
            taken.map { it.id.substringBefore(':') }.toSet(),
            "beide clips horen werk op te leveren: ${taken.map { it.id }}",
        )
    }
}

/**
 * Het volledige pad: bibliotheek → planner → wachtrij → thermische rem →
 * foutafhandeling. Zes modules die elkaar niet kennen, in één verhaal.
 */
class VolledigePijplijnTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)
    private val thermometer = Thermometer()
    private val queue = JobQueue(ThermalWorkGate(ThermalGovernor(), thermometer))

    @Test
    fun `geplande analyse wordt uitgevoerd, afgerond en verdwijnt uit de planning`() {
        val a = asset("a")
        AnalysisPlanner.plan(a, index, nowUs = 0L).forEach(queue::submit)

        // Werk de wachtrij af zoals een uitvoerder dat zou doen.
        var nu = 0L
        while (true) {
            val lease = queue.startNext(nowUs = nu) ?: break
            when (lease.job.kind) {
                is JobKind.ClipAnalysis -> {
                    index.write(a, AnalysisKind.SILENCES)
                    index.write(a, AnalysisKind.SCENES)
                }
                is JobKind.Transcription -> index.write(a, AnalysisKind.TRANSCRIPT)
                else -> Unit
            }
            queue.succeed(lease.job.id, nowUs = nu)
            nu += US_PER_SECOND
        }

        assertEquals(
            emptyList(),
            AnalysisPlanner.plan(a, index, nowUs = nu),
            "na afronding hoort er geen werk meer over te zijn",
        )
        assertEquals(1f, queue.progress().fraction, 1e-6f, "de wachtrij is klaar")
    }

    @Test
    fun `een heet toestel legt de hele pijplijn stil zonder werk te verliezen`() {
        val a = asset("a")
        val gepland = AnalysisPlanner.plan(a, index, nowUs = 0L)
        gepland.forEach(queue::submit)

        thermometer.status = ThermalStatus.CRITICAL
        assertNull(queue.startNext(nowUs = 0L), "kritiek toestel hoort stil te liggen")

        thermometer.status = ThermalStatus.NONE
        val lease = assertNotNull(
            queue.startNext(nowUs = 600 * US_PER_SECOND),
            "na afkoelen hoort het werk gewoon door te gaan",
        )
        assertTrue(
            lease.job.id in gepland.map { it.id },
            "er kwam iets anders uit de wachtrij dan er in ging",
        )
    }

    @Test
    fun `een netwerkfout in de cloudstap blokkeert de lokale analyse niet`() {
        val a = asset("a")
        AnalysisPlanner.plan(a, index, nowUs = 0L).forEach(queue::submit)

        // De transcriptie loopt tegen een storing aan; de clip-analyse is lokaal
        // en heeft daar niets mee te maken.
        val eerste = assertNotNull(queue.startNext(nowUs = 0L))
        if (eerste.job.kind is JobKind.Transcription) {
            queue.fail(eerste.job.id, nowUs = 0L, error = EditorError.NetworkUnavailable())
        } else {
            queue.succeed(eerste.job.id, nowUs = 0L)
        }

        assertNotNull(
            queue.startNext(nowUs = 1L),
            "een wachtende taak mag de rest van de wachtrij niet gijzelen",
        )
    }

    @Test
    fun `een verdwenen bronbestand stopt alleen zijn eigen taak`() {
        AnalysisPlanner.plan(asset("a"), index, nowUs = 0L).forEach(queue::submit)
        AnalysisPlanner.plan(asset("b"), index, nowUs = 0L).forEach(queue::submit)

        val lease = assertNotNull(queue.startNext(nowUs = 0L))
        queue.fail(
            lease.job.id,
            nowUs = 0L,
            error = EditorError.FileMissing("content://media/video/a"),
        )

        assertEquals(JobState.Failed, queue.job(lease.job.id)?.state)
        assertNotNull(queue.startNext(nowUs = 1L), "de rest van het werk gaat door")
    }
}
