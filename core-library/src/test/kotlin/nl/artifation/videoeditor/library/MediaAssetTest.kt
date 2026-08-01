package nl.artifation.videoeditor.library

import nl.artifation.videoeditor.model.US_PER_SECOND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MediaIdentityTest {

    @Test
    fun `hetzelfde bestand levert hetzelfde id op`() {
        val first = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 5 * US_PER_SECOND)
        val second = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 5 * US_PER_SECOND)

        assertEquals(first, second, "id moet stabiel zijn, was $first en $second")
    }

    @Test
    fun `de uri telt niet mee voor het id`() {
        // De documentkiezer geeft per keuze een andere content-uri voor hetzelfde bestand.
        val first = testAsset(uri = "content://media/1")
        val second = testAsset(uri = "content://media/9999")

        assertEquals(first.id, second.id, "id's liepen uiteen: ${first.id} vs ${second.id}")
    }

    @Test
    fun `ander materiaal levert een ander id op`() {
        val first = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 5 * US_PER_SECOND)
        val andereGrootte = MediaIdentity.of(sizeBytes = 2_000L, durationUs = 5 * US_PER_SECOND)
        val andereDuur = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 6 * US_PER_SECOND)

        assertNotEquals(first, andereGrootte, "beide $first")
        assertNotEquals(first, andereDuur, "beide $first")
    }

    @Test
    fun `de contenthash is doorslaggevend boven grootte en duur`() {
        val metHash = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 1_000L, contentHash = "abc")
        val andereStat = MediaIdentity.of(sizeBytes = 9_999L, durationUs = 7_777L, contentHash = "abc")

        assertEquals(metHash, andereStat, "hash moet winnen, was $metHash vs $andereStat")
    }

    @Test
    fun `een lege contenthash valt terug op grootte en duur`() {
        val leeg = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 1_000L, contentHash = "  ")
        val zonder = MediaIdentity.of(sizeBytes = 1_000L, durationUs = 1_000L)

        assertEquals(zonder, leeg, "was $leeg")
    }

    @Test
    fun `een id is bruikbaar als mapnaam`() {
        val id = testAsset().id

        assertEquals(16, id.length, "id was '$id'")
        assertTrue(id.all { it in "0123456789abcdef" }, "id bevat niet-hex tekens: '$id'")
    }
}

class MediaAssetMetadataTest {

    @Test
    fun `oriëntatie volgt uit de afmetingen`() {
        assertEquals(Orientation.PORTRAIT, testAsset(width = 1080, height = 1920).orientation)
        assertEquals(Orientation.LANDSCAPE, testAsset(width = 1920, height = 1080).orientation)
        assertEquals(Orientation.SQUARE, testAsset(width = 1080, height = 1080).orientation)
    }

    @Test
    fun `duur in seconden volgt uit de microseconden`() {
        val asset = testAsset(durationUs = 2_500_000L)

        assertEquals(2.5, asset.durationSeconds, 1e-9, "was ${asset.durationSeconds}")
    }

    @Test
    fun `geldige metadata levert geen problemen op`() {
        assertEquals(emptyList(), testAsset().validate())
    }

    @Test
    fun `duur nul wordt afgekeurd`() {
        val problems = testAsset(durationUs = 0L).validate()

        assertEquals(1, problems.size, "gevonden: $problems")
        assertEquals("asset.durationUs", problems.single().path)
    }

    @Test
    fun `lege uri en onmogelijke afmetingen worden allemaal gemeld`() {
        val problems = testAsset(uri = "", width = 0, height = -1, frameRate = 0f).validate()

        assertEquals(
            listOf("asset.uri", "asset.width", "asset.height", "asset.frameRate"),
            problems.map { it.path },
            "gevonden: $problems",
        )
    }
}
