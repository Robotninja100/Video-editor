package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PORTRAIT_1080 = OutputSpec(width = 1080, height = 1920, frameRate = 30)

class BitrateTest {

    @Test
    fun `de bitrate schaalt mee met het aantal pixels per seconde`() {
        val half = ExportPreset.recommendedVideoBitrate(
            OutputSpec(width = 540, height = 960, frameRate = 30),
        )
        val full = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080)

        // Twee keer zo breed én twee keer zo hoog is vier keer zoveel pixels.
        assertEquals(4f, full.toFloat() / half, 0.01f)
    }

    @Test
    fun `de bitrate schaalt mee met de framerate`() {
        val at30 = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080)
        val at60 = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080.copy(frameRate = 60))

        assertEquals(2f, at60.toFloat() / at30, 0.01f)
    }

    @Test
    fun `een hogere kwaliteit kost meer bits`() {
        val zuinig = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080, ExportQuality.ZUINIG)
        val gebalanceerd = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080, ExportQuality.GEBALANCEERD)
        val ruim = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080, ExportQuality.RUIM)

        assertTrue(zuinig < gebalanceerd, "$zuinig >= $gebalanceerd")
        assertTrue(gebalanceerd < ruim, "$gebalanceerd >= $ruim")
    }

    @Test
    fun `1080 op 30 fps komt uit op een gangbare bitrate`() {
        val bitrate = ExportPreset.recommendedVideoBitrate(PORTRAIT_1080)

        // Ergens tussen 4 en 7 Mbit/s; dat is wat platforms voor 1080p verwachten.
        assertTrue(bitrate in 4_000_000..7_000_000, "was $bitrate")
    }
}

class ExportPresetTest {

    @Test
    fun `elke preset heeft een uniek id`() {
        val ids = ExportPreset.ALL.map { it.id }

        assertEquals(ids.size, ids.distinct().size, "dubbele ids: $ids")
    }

    @Test
    fun `presets zijn op te zoeken op id`() {
        assertEquals(ExportPreset.SHORTS_1080, assertNotNull(ExportPreset.byId("shorts-1080")))
        assertNull(ExportPreset.byId("bestaat-niet"))
    }

    @Test
    fun `60 fps kost ongeveer het dubbele van 30 fps`() {
        val verhouding =
            ExportPreset.SHORTS_1080_60.videoBitrate.toFloat() / ExportPreset.SHORTS_1080.videoBitrate

        assertEquals(2f, verhouding, 0.01f)
    }

    @Test
    fun `de zuinige preset is daadwerkelijk de zuinigste`() {
        val zuinigste = ExportPreset.ALL.minBy { it.videoBitrate }

        assertEquals(ExportPreset.SHORTS_720, zuinigste)
    }

    @Test
    fun `alle presets hebben even afmetingen`() {
        // Oneven afmetingen breken op chroma-subsampling; dat merk je pas bij de encoder.
        for (preset in ExportPreset.ALL) {
            assertEquals(0, preset.outputSpec.width % 2, "${preset.id} heeft oneven breedte")
            assertEquals(0, preset.outputSpec.height % 2, "${preset.id} heeft oneven hoogte")
        }
    }

    @Test
    fun `een formaat op maat krijgt een passende preset`() {
        val spec = OutputSpec(width = 1440, height = 1440, frameRate = 24)
        val preset = ExportPreset.forSpec(spec)

        assertEquals(spec, preset.outputSpec)
        assertEquals(ExportPreset.recommendedVideoBitrate(spec), preset.videoBitrate)
        assertTrue(preset.label.contains("1440"), "label was ${preset.label}")
    }

    @Test
    fun `onzinnige bitrates worden geweigerd`() {
        assertFailsWith<IllegalArgumentException> {
            ExportPreset("x", "X", PORTRAIT_1080, videoBitrate = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ExportPreset("x", "X", PORTRAIT_1080, videoBitrate = 1, audioBitrate = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ExportPreset(" ", "X", PORTRAIT_1080, videoBitrate = 1)
        }
    }
}

class EstimatedSizeTest {

    @Test
    fun `een minuut op de standaardpreset komt uit op een plausibele grootte`() {
        val bytes = ExportPreset.SHORTS_1080.estimatedBytes(60 * US_PER_SECOND)

        // Ruim 5 Mbit/s beeld plus 128 kbit/s geluid, een minuut lang: ~40 MB.
        assertTrue(bytes in 30_000_000..60_000_000, "was $bytes")
    }

    @Test
    fun `twee keer zo lang is twee keer zo groot`() {
        val kort = ExportPreset.SHORTS_1080.estimatedBytes(30 * US_PER_SECOND)
        val lang = ExportPreset.SHORTS_1080.estimatedBytes(60 * US_PER_SECOND)

        assertEquals(2L * kort, lang)
    }

    @Test
    fun `een lege tijdlijn levert geen bestand op`() {
        assertEquals(0L, ExportPreset.SHORTS_1080.estimatedBytes(0L))
        assertEquals(0L, ExportPreset.SHORTS_1080.estimatedBytes(-1L))
    }

    @Test
    fun `een uur in de ruimste preset loopt niet over`() {
        val bytes = ExportPreset.MASTER_1080.estimatedBytes(3_600 * US_PER_SECOND)

        assertTrue(bytes > 0L, "overloop bij lange duur: $bytes")
    }
}
