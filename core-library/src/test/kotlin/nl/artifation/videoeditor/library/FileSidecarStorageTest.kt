package nl.artifation.videoeditor.library

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSidecarStorageTest {

    private val root: File = Files.createTempDirectory("sidecars").toFile()
    private val storage = FileSidecarStorage(root)

    @AfterTest
    fun ruimOp() {
        root.deleteRecursively()
    }

    private fun record(assetId: String = "asset-1", kind: AnalysisKind = AnalysisKind.SILENCES) =
        SidecarRecord(
            assetId = assetId,
            kind = kind,
            analysisVersion = kind.currentVersion,
            sourceRevision = 7L,
            payload = """{"silences":[]}""",
            writtenAtEpochMs = 1_700_000_000_000L,
        )

    @Test
    fun `een record overleeft schrijven en lezen`() {
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())

        assertEquals(record(), storage.read(pad))
    }

    @Test
    fun `submappen worden aangemaakt`() {
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())

        assertTrue(File(root, pad).isFile, "verwacht bestand op $pad")
    }

    @Test
    fun `een onbekend pad levert niets op`() {
        assertNull(storage.read(SidecarPaths.of("bestaat-niet", AnalysisKind.SCENES)))
        assertFalse(storage.exists(SidecarPaths.of("bestaat-niet", AnalysisKind.SCENES)))
    }

    @Test
    fun `een kapot bestand levert niets op in plaats van een exception`() {
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())
        File(root, pad).writeText("{ dit is geen json")

        assertNull(storage.read(pad), "opnieuw analyseren is beter dan half interpreteren")
    }

    @Test
    fun `onbekende velden blokkeren het lezen niet`() {
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())
        val bestand = File(root, pad)
        bestand.writeText(bestand.readText().replaceFirst("{", """{"ietsNieuws":3,"""))

        assertNotNull(storage.read(pad))
    }

    @Test
    fun `opnieuw schrijven vervangt en laat niets achter`() {
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())
        storage.write(pad, record().copy(sourceRevision = 8L))

        assertEquals(8L, assertNotNull(storage.read(pad)).sourceRevision)
        assertEquals(listOf(pad), storage.paths(), "geen achtergebleven tijdelijke bestanden")
    }

    @Test
    fun `verwijderen maakt het pad weer onbekend`() {
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())

        assertTrue(storage.delete(pad))
        assertNull(storage.read(pad))
        assertFalse(storage.delete(pad), "twee keer verwijderen levert de tweede keer niets op")
    }

    @Test
    fun `alle paden komen gesorteerd terug`() {
        val stiltes = SidecarPaths.of("b-asset", AnalysisKind.SILENCES)
        val scenes = SidecarPaths.of("a-asset", AnalysisKind.SCENES)
        storage.write(stiltes, record("b-asset", AnalysisKind.SILENCES))
        storage.write(scenes, record("a-asset", AnalysisKind.SCENES))

        assertEquals(listOf(scenes, stiltes).sorted(), storage.paths())
    }

    @Test
    fun `een lege map levert een lege lijst op`() {
        assertEquals(emptyList(), FileSidecarStorage(File(root, "nog-niets")).paths())
    }

    @Test
    fun `een pad dat buiten de map wijst wordt geweigerd`() {
        // De paden komen van SidecarPaths, maar deze klasse maakt bestanden aan
        // en hoort niet te vertrouwen op wie hem aanroept.
        assertFailsWith<IllegalArgumentException> { storage.read("../buiten.json") }
        assertFailsWith<IllegalArgumentException> { storage.write("../buiten.json", record()) }
    }

    @Test
    fun `de index kan er gewoon op werken`() {
        val index = SidecarIndex(storage)
        val pad = SidecarPaths.of("asset-1", AnalysisKind.SILENCES)
        storage.write(pad, record())

        assertNotNull(index.record("asset-1", AnalysisKind.SILENCES))
    }
}
