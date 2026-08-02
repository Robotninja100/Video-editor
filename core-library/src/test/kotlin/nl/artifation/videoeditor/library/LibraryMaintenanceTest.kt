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

        assertEquals(
            listOf(ongebruikt.id),
            kandidaten.map { it.id },
            "kandidaten: ${kandidaten.map { it.displayName }}",
        )
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
        storage.write(
            SidecarPaths.of("verdwenen", AnalysisKind.SCENES),
            SidecarRecord("verdwenen", AnalysisKind.SCENES, 1, 0L),
        )

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

/**
 * Aliassen zijn er omdat Android per keuze een nieuwe `content://`-uri geeft.
 * Wie ze bij het opruimen negeert, gooit bereikbaar materiaal weg — inclusief
 * de analyses, en dat is betaald cloudwerk.
 */
class AliasAwareCleanupTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)

    // Tweemaal hetzelfde bestand: gelijke grootte en duur, dus hetzelfde id.
    private val eerste = testAsset(uri = "content://weg", sizeBytes = 99L)
    private val tweede = testAsset(uri = "content://nog-hier", sizeBytes = 99L)
    private val catalog = MediaCatalog().add(eerste).add(tweede)

    @Test
    fun `de tweede import telt mee bij de vraag of het bestand er nog is`() {
        val ontbrekend = LibraryMaintenance.missingAssets(catalog) { it == "content://nog-hier" }

        assertEquals(emptyList(), ontbrekend, "het bestand is gewoon te openen via de tweede uri")
    }

    @Test
    fun `sweep gooit geen materiaal weg dat via een alias bereikbaar is`() {
        for (kind in AnalysisKind.entries) index.write(eerste, kind)

        val resultaat = LibraryMaintenance.sweep(catalog, index) { it == "content://nog-hier" }

        assertEquals(emptyList(), resultaat.removedAssets)
        assertEquals(emptyList(), resultaat.deletedSidecars, "de analyses zijn betaald werk")
        assertEquals(1, resultaat.catalog.size)
    }

    @Test
    fun `een echt verdwenen bestand gaat wel weg`() {
        val resultaat = LibraryMaintenance.sweep(catalog, index) { false }

        assertEquals(1, resultaat.removedAssets.size)
        assertTrue(resultaat.catalog.isEmpty)
    }

    @Test
    fun `sweep laat een maskvideo staan die een project nog gebruikt`() {
        val ander = testAsset(uri = "content://ander", sizeBytes = 7L)
        val maskPad = SidecarPaths.mask(eerste.id, 0)
        index.writeMask(eerste, 0)
        val projecten = listOf(projectUsing("content://ander", maskUris = listOf(maskPad)))

        val resultaat = LibraryMaintenance.sweep(
            catalog = catalog.add(ander),
            index = index,
            projects = projecten,
            isPresent = { it == "content://ander" },
        )

        assertFalse(maskPad in resultaat.deletedSidecars, "orphanSidecars spaarde hem, sweep gooide hem alsnog weg")
        assertTrue(storage.exists(maskPad), "de montage van de gebruiker is stuk")
    }
}

class MaskLifecycleTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)
    private val asset = testAsset()

    @Test
    fun `subjects opnieuw doen gooit de maskvideo's van de vorige ronde weg`() {
        index.write(asset, AnalysisKind.SUBJECTS)
        repeat(3) { index.writeMask(asset, it) }

        index.invalidate(asset.id, AnalysisKind.SUBJECTS)

        assertEquals(emptyList(), index.maskIndices(asset.id), "spookmaskers uit de vorige ronde")
    }

    @Test
    fun `een andere analyse laat de maskvideo's met rust`() {
        index.writeMask(asset, 0)

        index.invalidate(asset.id, AnalysisKind.SCENES)

        assertEquals(listOf(0), index.maskIndices(asset.id))
    }

    @Test
    fun `een verouderde maskvideo zet het asset in de werkvoorraad`() {
        val oud = asset.copy(sourceRevision = asset.sourceRevision - 1)
        for (kind in AnalysisKind.entries) index.write(asset, kind)
        index.writeMask(oud, 0)

        val status = index.status(asset)

        assertEquals(listOf(0), status.staleMaskIndices)
        assertFalse(status.isComplete, "een verouderde mask is openstaand werk")
        assertEquals(listOf(asset.id), index.pending(MediaCatalog.of(listOf(asset))).map { it.assetId })
    }
}

class StaleAliasTest {

    @Test
    fun `een verwijderd asset herleeft niet als ander materiaal op zijn uri staat`() {
        val oud = testAsset(uri = "content://1", displayName = "oud.mp4", sizeBytes = 1L)
        val zelfdeBestand = testAsset(uri = "content://2", displayName = "oud.mp4", sizeBytes = 1L)
        val nieuw = testAsset(uri = "content://2", displayName = "nieuw.mp4", sizeBytes = 2L)

        val catalog = MediaCatalog().add(oud).add(zelfdeBestand).add(nieuw)
        assertEquals("nieuw.mp4", catalog.byUri("content://2")?.displayName)

        val na = catalog.remove(nieuw.id)

        assertEquals(
            null,
            na.byUri("content://2"),
            "het oude asset dook weer op onder een uri die niet meer van hem is",
        )
    }
}

class IdentityWithoutSizeTest {

    @Test
    fun `zonder grootte en zonder hash vallen twee clips van dezelfde duur niet samen`() {
        val strand = testAsset(uri = "content://1", displayName = "strand.mp4", sizeBytes = 0L)
        val feestje = testAsset(uri = "content://2", displayName = "feestje.mp4", sizeBytes = 0L)

        assertTrue(strand.id != feestje.id, "beide kregen id ${strand.id}")

        val catalog = MediaCatalog().add(strand).add(feestje)
        assertEquals(2, catalog.size)
        assertEquals("feestje.mp4", catalog.byUri("content://2")?.displayName)
    }

    @Test
    fun `met een grootte blijft dezelfde inhoud wel hetzelfde asset`() {
        val een = testAsset(uri = "content://1", sizeBytes = 5L)
        val twee = testAsset(uri = "content://2", sizeBytes = 5L)

        assertEquals(een.id, twee.id)
    }
}
