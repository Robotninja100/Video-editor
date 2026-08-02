package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun crop(left: Float, right: Float) =
    PlannedEffect.Crop(left = left, right = right, bottom = -1f, top = 1f)

private fun cue(startUs: Us, endUs: Us, text: String) = Cue(startUs, endUs, text)

class CropAtTest {

    private val path = listOf(
        Keyframe(0L, crop(-1f, 0f)),
        Keyframe(US_PER_SECOND, crop(0f, 1f)),
    )

    @Test
    fun `precies op een keyframe komt de waarde van dat keyframe eruit`() {
        assertEquals(crop(-1f, 0f), path.cropAt(0L))
        assertEquals(crop(0f, 1f), path.cropAt(US_PER_SECOND))
    }

    @Test
    fun `daartussen wordt lineair geinterpoleerd`() {
        val halverwege = assertNotNull(path.cropAt(US_PER_SECOND / 2))

        assertEquals(-0.5f, halverwege.left, 1e-5f)
        assertEquals(0.5f, halverwege.right, 1e-5f)
    }

    @Test
    fun `een kwart van de weg is een kwart van het verschil`() {
        val kwart = assertNotNull(path.cropAt(US_PER_SECOND / 4))

        assertEquals(-0.75f, kwart.left, 1e-5f)
    }

    @Test
    fun `buiten het pad wordt de rand vastgehouden`() {
        assertEquals(crop(-1f, 0f), path.cropAt(-US_PER_SECOND), "vóór het pad")
        assertEquals(crop(0f, 1f), path.cropAt(10 * US_PER_SECOND), "ná het pad")
    }

    @Test
    fun `een leeg pad levert niets op`() {
        assertNull(emptyList<Keyframe<PlannedEffect.Crop>>().cropAt(0L))
    }

    @Test
    fun `een pad van één keyframe is een vaste crop`() {
        val vast = listOf(Keyframe(US_PER_SECOND, crop(-0.5f, 0.5f)))

        assertEquals(crop(-0.5f, 0.5f), vast.cropAt(0L))
        assertEquals(crop(-0.5f, 0.5f), vast.cropAt(5 * US_PER_SECOND))
    }

    @Test
    fun `twee keyframes op hetzelfde tijdstip leveren geen deling door nul op`() {
        val ontaard = listOf(
            Keyframe(0L, crop(-1f, 0f)),
            Keyframe(0L, crop(0f, 1f)),
            Keyframe(US_PER_SECOND, crop(0f, 1f)),
        )

        assertNotNull(ontaard.cropAt(0L))
    }

    @Test
    fun `ook de verticale randen lopen mee`() {
        val verticaal = listOf(
            Keyframe(0L, PlannedEffect.Crop(left = -1f, right = 1f, bottom = -1f, top = 0f)),
            Keyframe(US_PER_SECOND, PlannedEffect.Crop(left = -1f, right = 1f, bottom = 0f, top = 1f)),
        )
        val halverwege = assertNotNull(verticaal.cropAt(US_PER_SECOND / 2))

        assertEquals(-0.5f, halverwege.bottom, 1e-5f)
        assertEquals(0.5f, halverwege.top, 1e-5f)
    }
}

class ActiveCueTest {

    private val cues = listOf(
        cue(0L, US_PER_SECOND, "eerste"),
        cue(US_PER_SECOND, 2 * US_PER_SECOND, "tweede"),
        cue(3 * US_PER_SECOND, 4 * US_PER_SECOND, "derde"),
    )

    @Test
    fun `midden in een cue komt die cue eruit`() {
        assertEquals("eerste", assertNotNull(cues.activeAt(US_PER_SECOND / 2)).text)
        assertEquals("derde", assertNotNull(cues.activeAt(3 * US_PER_SECOND + 1)).text)
    }

    @Test
    fun `op de grens telt alleen de nieuwe cue`() {
        // Anders staan er op één frame twee regels, of knippert de verkeerde.
        assertEquals("tweede", assertNotNull(cues.activeAt(US_PER_SECOND)).text)
    }

    @Test
    fun `in een gat tussen cues staat er niets`() {
        assertNull(cues.activeAt(2 * US_PER_SECOND + 100L))
    }

    @Test
    fun `voor de eerste en na de laatste cue staat er niets`() {
        assertNull(cues.activeAt(-1L))
        assertNull(cues.activeAt(4 * US_PER_SECOND))
    }

    @Test
    fun `zonder cues staat er nooit iets`() {
        assertNull(emptyList<Cue>().activeAt(0L))
        assertEquals(-1, emptyList<Cue>().activeIndexAt(0L))
    }

    @Test
    fun `de index wijst dezelfde cue aan`() {
        val atUs = US_PER_SECOND / 2
        val index = cues.activeIndexAt(atUs)

        assertEquals(0, index)
        assertEquals(cues.activeAt(atUs), cues[index])
    }

    @Test
    fun `een gat levert index min één op`() {
        assertEquals(-1, cues.activeIndexAt(2 * US_PER_SECOND + 100L))
    }
}
