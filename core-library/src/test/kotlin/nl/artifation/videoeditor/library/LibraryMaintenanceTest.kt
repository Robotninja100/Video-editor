package nl.artifation.videoeditor.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryMaintenanceTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)

    private val gebruikt = testAsset(uri = "file:///gebruikt.mp4", sizeBytes = 1L)
    private val ongebruikt = testAsset(uri = "file:///ongebruikt.mp4", sizeBytes = 2L)
    private val catalog = MediaCatalog.of(listOf(gebruikt, ongebruikt))

    @Test
    fun `een project verwijst naar zijn bronclips en naar zijn maskvideo's`() {
        val maskPad = SidecarPaths.mask(gebruikt.id, 0)
        val project = projectUsing("file:///gebruikt.mp4", maskUris = listOf(maskPad))

        assertEquals(setOf("file:///gebruikt.mp4", maskPad), LibraryMaintenance.referencedUris(project))
    }

    @Test
    fun `materiaal dat in geen enkel project voorkomt is opruimkandidaat`() {
        val projects = listOf(projectUsing("file:///gebruikt.mp4"))

        val kandidaten = LibraryMaintenance.unusedAssets(catalog, projects)

        assertEquals(listOf(ongebruikt.id), kandidaten.map { it.id }, "kandidaten: ${kandidaten.map { it.displayName }}")
    }

    @Test
    fun `zonder projecten is alles opruimkandidaat`() {
        assertEquals(catalog.assets, LibraryMaintenance.unusedAssets(catalog, emptyList()))
    }

    @Test
    fun `een project dat een alias-uri gebruikt houdt het asset in gebruik`() {
        // Tweede import van hetzelfde bestand: het project verwijst naar de andere uri.
        val metAlias = catalog.add(testAsset(uri = "content://tweede", sizeBytes = 1L))
        val projects = listOf(projectUsing("content://tweede"))

        assertEquals(setOf(gebruikt.id), LibraryMaintenance.usedAssetIds(metAlias, projects))
    }

    @Test
    fun `verdwenen bronbestanden zijn te herkennen zonder bestandssysteem`() {
        val weg = LibraryMaintenance.missingAssets(catalog) { it != ongebruikt.uri }

        assertEquals(listOf(ongebruikt.id), weg.map { it.id }, "weg: ${weg.map { it.uri }}")
    }

    @Test
    fun `forget verwijdert het asset samen met zijn sidecars`() {
        AnalysisKind.entries.forEach { index.write(ongebruikt, it) }
        index.write(gebruikt, AnalysisKind.SCENES)

        val result = LibraryMaintenance.forget(catalog, index, ongebruikt.id)

        assertEquals(listOf(gebruikt.id), result.catalog.assets.map { it.id })
        assertEquals(AnalysisKind.entries.size, result.deletedSidecars.size, "verwijderd: ${result.deletedSidecars}")
        assertTrue(index.state(gebruikt, AnalysisKind.SCENES).isUsable, "de rest bleef niet staan")
    }

    @Test
    fun `forget van een onbekend asset verandert niets`() {
        index.write(gebruikt, AnalysisKind.SCENES)

        val result = LibraryMaintenance.forget(catalog, index, "onbekend")

        assertEquals(catalog, result.catalog)
        assertEquals(emptyList(), result.deletedSidecars)
        assertEquals(emptyList(), result.removedAssets)
    }

    @Test
    fun `sidecars zonder asset zijn wezen`() {
        index.write(gebruikt, AnalysisKind.SCENES)
        storage.write(SidecarPaths.of("verdwenen", AnalysisKind.SCENES), SidecarRecord("verdwenen", AnalysisKind.SCENES, 1, 0L))

        val wezen = LibraryMaintenance.orphanSidecars(catalog, storage)

        assertEquals(listOf(".cache/verdwenen/scenes.json"), wezen)
    }

    @Test
    fun `een maskvideo die een project nog gebruikt is geen wees`() {
        // De montage verwijst er rechtstreeks naar; weggooien breekt de blur.
        val maskPad = SidecarPaths.mask("verdwenen", 0)
        storage.write(maskPad, SidecarRecord("verdwenen", AnalysisKind.SUBJECTS, 1, 0L))
        val projects = listOf(projectUsing("file:///gebruikt.mp4", maskUris = listOf(maskPad)))

        assertEquals(emptyList(), LibraryMaintenance.orphanSidecars(catalog, storage, projects))
    }

    @Test
    fun `bestanden buiten de cachemap worden niet als wees geteld`() {
        storage.write("elders/transcript.json", SidecarRecord("x", AnalysisKind.TRANSCRIPT, 1, 0L))

        assertEquals(emptyList(), LibraryMaintenance.orphanSidecars(catalog, storage))
    }
}

class LibrarySweepTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)

    private val gebruikt = testAsset(uri = "file:///gebruikt.mp4", sizeBytes = 1L)
    private val ongebruikt = testAsset(uri = "file:///ongebruikt.mp4", sizeBytes = 2L)
    private val catalog = MediaCatalog.of(listOf(gebruikt, ongebruikt))
    private val projects = listOf(projectUsing("file:///gebruikt.mp4"))

    @Test
    fun `sweep verwijdert materiaal dat verdwenen én ongebruikt is`() {
        AnalysisKind.entries.forEach { index.write(ongebruikt, it) }

        val result = LibraryMaintenance.sweep(catalog, index, projects) { it != ongebruikt.uri }

        assertEquals(listOf(ongebruikt.id), result.removedAssets.map { it.id })
        assertEquals(listOf(gebruikt.id), result.catalog.assets.map { it.id })
        assertEquals(AnalysisKind.entries.size, result.deletedSidecars.size, "verwijderd: ${result.deletedSidecars}")
        assertEquals(emptyList(), storage.paths(), "opslag: ${storage.paths()}")
    }

    @Test
    fun `sweep laat ongebruikt materiaal staan zolang het bestand er nog is`() {
        // Wat de gebruiker nog op schijf heeft staan, gooit de opruimer niet weg.
        val result = LibraryMaintenance.sweep(catalog, index, projects) { true }

        assertEquals(catalog, result.catalog)
        assertEquals(emptyList(), result.removedAssets)
    }

    @Test
    fun `sweep laat gebruikt materiaal staan ook als het bestand weg is`() {
        // Het project verwijst er nog naar; verwijderen zou de montage stilzwijgend slopen.
        val result = LibraryMaintenance.sweep(catalog, index, projects) { false }

        assertEquals(listOf(gebruikt.id), result.catalog.assets.map { it.id })
        assertEquals(listOf(ongebruikt.id), result.removedAssets.map { it.id })
    }

    @Test
    fun `sweep ruimt ook sidecars op die nergens meer bij horen`() {
        storage.write(
            SidecarPaths.of("verdwenen", AnalysisKind.SILENCES),
            SidecarRecord("verdwenen", AnalysisKind.SILENCES, 1, 0L),
        )

        val result = LibraryMaintenance.sweep(catalog, index, projects) { true }

        assertEquals(listOf(".cache/verdwenen/silences.json"), result.deletedSidecars)
        assertFalse(storage.exists(".cache/verdwenen/silences.json"))
    }

    @Test
    fun `sweep raakt de sidecars van gebruikt materiaal niet aan`() {
        index.write(gebruikt, AnalysisKind.TRANSCRIPT)

        LibraryMaintenance.sweep(catalog, index, projects) { false }

        assertTrue(index.state(gebruikt, AnalysisKind.TRANSCRIPT).isUsable, "opslag: ${storage.paths()}")
    }
}
