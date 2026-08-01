package nl.artifation.videoeditor.library

import kotlinx.serialization.json.Json
import nl.artifation.videoeditor.model.US_PER_SECOND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MediaCatalogTest {

    @Test
    fun `een dubbele import levert geen tweede asset op`() {
        val asset = testAsset()

        val catalog = MediaCatalog().add(asset).add(asset)

        assertEquals(1, catalog.size, "catalogus bevat ${catalog.assets.map { it.displayName }}")
    }

    @Test
    fun `een dubbele import onder een andere uri blijft via beide uri's vindbaar`() {
        val eerste = testAsset(uri = "content://media/1")
        val tweede = testAsset(uri = "content://media/2")

        val catalog = MediaCatalog().add(eerste).add(tweede)

        assertEquals(1, catalog.size, "catalogus: ${catalog.assets}")
        assertEquals(eerste.id, catalog.byUri("content://media/1")?.id)
        assertEquals(eerste.id, catalog.byUri("content://media/2")?.id, "alias ontbreekt: ${catalog.aliases}")
    }

    @Test
    fun `de eerste import bepaalt het tijdstip van toevoegen`() {
        val eerste = testAsset(uri = "content://media/1", addedAtEpochMs = 100L)
        val tweede = testAsset(uri = "content://media/2", addedAtEpochMs = 900L)

        val bewaard = MediaCatalog().add(eerste).add(tweede).assets.single()

        assertEquals(100L, bewaard.addedAtEpochMs, "was ${bewaard.addedAtEpochMs}")
    }

    @Test
    fun `een tweede import van dezelfde uri verandert niets`() {
        val asset = testAsset()
        val catalog = MediaCatalog().add(asset)

        assertSame(catalog, catalog.add(asset), "er is een nieuwe catalogus gemaakt zonder wijziging")
    }

    @Test
    fun `opzoeken op id en op uri geven hetzelfde asset`() {
        val asset = testAsset()
        val catalog = MediaCatalog().add(asset)

        assertEquals(asset, catalog.get(asset.id))
        assertEquals(asset, catalog.byUri(asset.uri))
        assertTrue(catalog.contains(asset.id))
    }

    @Test
    fun `een onbekend id of uri levert niets op`() {
        val catalog = MediaCatalog().add(testAsset())

        assertNull(catalog.get("onbekend"))
        assertNull(catalog.byUri("content://media/999"))
    }

    @Test
    fun `verwijderen haalt ook de alias weg`() {
        val asset = testAsset(uri = "content://media/1")
        val catalog = MediaCatalog()
            .add(asset)
            .add(testAsset(uri = "content://media/2"))

        val na = catalog.remove(asset.id)

        assertTrue(na.isEmpty, "resteert: ${na.assets}")
        assertNull(na.byUri("content://media/2"), "alias bleef staan: ${na.aliases}")
    }

    @Test
    fun `verwijderen van een onbekend id verandert niets`() {
        val catalog = MediaCatalog().add(testAsset())

        assertSame(catalog, catalog.remove("onbekend"))
    }

    @Test
    fun `replace werkt metadata bij zonder de volgorde te veranderen`() {
        val eerste = testAsset(uri = "a", sizeBytes = 1L, durationUs = US_PER_SECOND)
        val tweede = testAsset(uri = "b", sizeBytes = 2L, durationUs = US_PER_SECOND)
        val catalog = MediaCatalog.of(listOf(eerste, tweede))

        val na = catalog.replace(eerste.copy(sourceRevision = 99L, displayName = "hernoemd.mp4"))

        assertEquals(listOf("hernoemd.mp4", tweede.displayName), na.assets.map { it.displayName })
        assertEquals(99L, na.get(eerste.id)?.sourceRevision, "was ${na.get(eerste.id)?.sourceRevision}")
    }

    @Test
    fun `replace van een onbekend asset doet niets`() {
        val catalog = MediaCatalog().add(testAsset(uri = "a", sizeBytes = 1L))

        assertSame(catalog, catalog.replace(testAsset(uri = "b", sizeBytes = 2L)))
    }

    @Test
    fun `nieuw materiaal op een bestaande uri neemt die uri over`() {
        // Een her-encode op dezelfde plek: het oude asset blijft bestaan, want
        // zijn sidecars horen nog bij de oude inhoud.
        val oud = testAsset(uri = "file:///clip.mp4", sizeBytes = 100L)
        val nieuw = testAsset(uri = "file:///clip.mp4", sizeBytes = 200L)

        val catalog = MediaCatalog().add(oud).add(nieuw)

        assertEquals(2, catalog.size, "catalogus: ${catalog.assets.map { it.id }}")
        assertEquals(nieuw.id, catalog.byUri("file:///clip.mp4")?.id)
    }

    @Test
    fun `totalen tellen alle assets op`() {
        val catalog = MediaCatalog.of(
            listOf(
                testAsset(uri = "a", durationUs = US_PER_SECOND, sizeBytes = 100L),
                testAsset(uri = "b", durationUs = 2 * US_PER_SECOND, sizeBytes = 250L),
            ),
        )

        assertEquals(3 * US_PER_SECOND, catalog.totalDurationUs, "was ${catalog.totalDurationUs}")
        assertEquals(350L, catalog.totalSizeBytes, "was ${catalog.totalSizeBytes}")
    }

    @Test
    fun `serialisatie heen en weer levert dezelfde catalogus op`() {
        val catalog = MediaCatalog()
            .add(testAsset(uri = "content://media/1"))
            .add(testAsset(uri = "content://media/2"))

        val json = Json.encodeToString(catalog)

        assertEquals(catalog, Json.decodeFromString<MediaCatalog>(json), "json was $json")
    }
}
