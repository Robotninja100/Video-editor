package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.US_PER_SECOND
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarStoreTest {

    private val directory: File = Files.createTempDirectory("sidecars").toFile()
    private val store = FileSidecarStore(directory)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val sidecar = Sidecar(
        sourceUri = "content://media/external/video/42",
        sourceDurationUs = 12 * US_PER_SECOND,
        silences = listOf(SilenceInterval(US_PER_SECOND, 2 * US_PER_SECOND, -62f)),
        loudness = LoudnessResult(integratedLufs = -18.4f, samplePeakDbfs = -1.2f),
        cues = listOf(Cue(0L, US_PER_SECOND, "hallo")),
        sceneCutsUs = listOf(4 * US_PER_SECOND),
        reframePath = listOf(Keyframe(0L, NormRect(0.2f, 0f, 0.8f, 1f))),
    )

    @Test
    fun `een sidecar overleeft schrijven en lezen`() {
        store.write(sidecar)

        assertEquals(sidecar, store.read(sidecar.sourceUri))
    }

    @Test
    fun `een onbekende bron levert niets op`() {
        assertNull(store.read("content://media/external/video/999"))
    }

    @Test
    fun `de versie staat altijd in het bestand`() {
        store.write(sidecar)

        val text = store.fileFor(sidecar.sourceUri).readText()
        assertTrue(
            text.contains("\"formatVersion\": 1") || text.contains("\"formatVersion\":1"),
            "zonder versie in het bestand wordt een oud bestand later als huidig gelezen",
        )
    }

    @Test
    fun `een sidecar van een andere formaatversie wordt verworpen`() {
        store.write(sidecar)
        val file = store.fileFor(sidecar.sourceUri)
        file.writeText(file.readText().replace("\"formatVersion\":1", "\"formatVersion\":99")
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99"))

        assertNull(store.read(sidecar.sourceUri), "liever opnieuw analyseren dan verkeerd interpreteren")
    }

    @Test
    fun `een kapot bestand levert niets op in plaats van een exception`() {
        store.write(sidecar)
        store.fileFor(sidecar.sourceUri).writeText("{ dit is geen json")

        assertNull(store.read(sidecar.sourceUri))
    }

    @Test
    fun `een bestand met een andere bron-URI wordt verworpen`() {
        // Beschermt tegen een hashbotsing: die valt hier op in plaats van stilletjes
        // de analyse van een andere clip terug te geven.
        val file = store.fileFor("content://a")
        directory.mkdirs()
        file.writeText(SidecarJson.encode(sidecar.copy(sourceUri = "content://b")))

        assertNull(store.read("content://a"))
    }

    @Test
    fun `onbekende velden blokkeren het lezen niet`() {
        store.write(sidecar)
        val file = store.fileFor(sidecar.sourceUri)
        file.writeText(file.readText().replaceFirst("{", "{\"ietsNieuws\": 3,"))

        assertNotNull(store.read(sidecar.sourceUri))
    }

    @Test
    fun `opnieuw schrijven vervangt de vorige versie`() {
        store.write(sidecar)
        store.write(sidecar.copy(sceneCutsUs = emptyList()))

        assertEquals(emptyList(), assertNotNull(store.read(sidecar.sourceUri)).sceneCutsUs)
        assertEquals(
            1, directory.listFiles().orEmpty().size,
            "geen achtergebleven tijdelijke bestanden",
        )
    }

    @Test
    fun `verwijderen maakt de bron weer onbekend`() {
        store.write(sidecar)
        store.delete(sidecar.sourceUri)

        assertNull(store.read(sidecar.sourceUri))
    }

    @Test
    fun `de map wordt aangemaakt als hij nog niet bestaat`() {
        val nested = FileSidecarStore(File(directory, "diep/nog-dieper"))
        nested.write(sidecar)

        assertEquals(sidecar, nested.read(sidecar.sourceUri))
    }
}
