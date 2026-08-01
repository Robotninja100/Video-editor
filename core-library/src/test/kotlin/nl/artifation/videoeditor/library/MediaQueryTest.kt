package nl.artifation.videoeditor.library

import nl.artifation.videoeditor.model.US_PER_SECOND
import kotlin.test.Test
import kotlin.test.assertEquals

private val kort = testAsset(
    uri = "a",
    displayName = "Avond in Utrecht.mp4",
    durationUs = 2 * US_PER_SECOND,
    sizeBytes = 10L,
    addedAtEpochMs = 300L,
)

private val middel = testAsset(
    uri = "b",
    displayName = "berg.mp4",
    durationUs = 5 * US_PER_SECOND,
    sizeBytes = 20L,
    addedAtEpochMs = 100L,
    width = 1920,
    height = 1080,
    hasAudio = false,
)

private val lang = testAsset(
    uri = "c",
    displayName = "Concert.mp4",
    durationUs = 9 * US_PER_SECOND,
    sizeBytes = 30L,
    addedAtEpochMs = 200L,
)

private val catalog = MediaCatalog.of(listOf(kort, middel, lang))

class MediaSortTest {

    @Test
    fun `sorteren op naam is hoofdletterongevoelig`() {
        val namen = catalog.sorted(MediaSort.NAME).map { it.displayName }

        assertEquals(listOf("Avond in Utrecht.mp4", "berg.mp4", "Concert.mp4"), namen)
    }

    @Test
    fun `sorteren op duur loopt van kort naar lang`() {
        assertEquals(listOf(kort, middel, lang), catalog.sorted(MediaSort.DURATION))
    }

    @Test
    fun `aflopend sorteren draait de volgorde om`() {
        assertEquals(listOf(lang, middel, kort), catalog.sorted(MediaSort.DURATION, descending = true))
    }

    @Test
    fun `sorteren op datum gebruikt het tijdstip van toevoegen en niet de importvolgorde`() {
        assertEquals(listOf(middel, lang, kort), catalog.sorted(MediaSort.ADDED))
    }

    @Test
    fun `gelijke waarden krijgen een reproduceerbare volgorde`() {
        val eenA = testAsset(uri = "x", displayName = "zelfde.mp4", sizeBytes = 1L, addedAtEpochMs = 5L)
        val eenB = testAsset(uri = "y", displayName = "zelfde.mp4", sizeBytes = 2L, addedAtEpochMs = 5L)
        val opNaam = MediaCatalog.of(listOf(eenA, eenB)).sorted(MediaSort.NAME)
        val omgekeerdGevuld = MediaCatalog.of(listOf(eenB, eenA)).sorted(MediaSort.NAME)

        assertEquals(opNaam, omgekeerdGevuld, "volgorde hing van de importvolgorde af: $opNaam vs $omgekeerdGevuld")
    }
}

class MediaSearchTest {

    @Test
    fun `zoeken op naam is hoofdletterongevoelig`() {
        assertEquals(listOf(middel), catalog.search("BERG"))
    }

    @Test
    fun `zoeken vindt een deel van de naam`() {
        assertEquals(listOf(kort), catalog.search("utrecht"))
    }

    @Test
    fun `een lege zoekterm levert alles op`() {
        assertEquals(catalog.assets, catalog.search("   "))
    }

    @Test
    fun `zonder treffers is de uitkomst leeg`() {
        assertEquals(emptyList(), catalog.search("zonsondergang"))
    }
}

class MediaFilterTest {

    @Test
    fun `filteren op minimale duur`() {
        val gevonden = catalog.filter(MediaFilter(minDurationUs = 5 * US_PER_SECOND))

        assertEquals(listOf(middel, lang), gevonden, "gevonden: ${gevonden.map { it.displayName }}")
    }

    @Test
    fun `filteren op maximale duur`() {
        assertEquals(listOf(kort, middel), catalog.filter(MediaFilter(maxDurationUs = 5 * US_PER_SECOND)))
    }

    @Test
    fun `filteren op materiaal zonder audio`() {
        assertEquals(listOf(middel), catalog.filter(MediaFilter(hasAudio = false)))
    }

    @Test
    fun `filteren op oriëntatie`() {
        assertEquals(listOf(kort, lang), catalog.filter(MediaFilter(orientation = Orientation.PORTRAIT)))
    }

    @Test
    fun `een leeg filter laat alles door`() {
        assertEquals(catalog.assets, catalog.filter(MediaFilter()))
    }

    @Test
    fun `filtervoorwaarden gelden allemaal tegelijk`() {
        val filter = MediaFilter(query = "e", minDurationUs = 5 * US_PER_SECOND, hasAudio = true)

        assertEquals(listOf(lang), catalog.filter(filter), "gevonden: ${catalog.filter(filter)}")
    }

    @Test
    fun `filteren met een volgorde levert de gesorteerde selectie`() {
        val gevonden = catalog.filter(MediaFilter(hasAudio = true), MediaSort.DURATION, descending = true)

        assertEquals(listOf(lang, kort), gevonden)
    }
}
