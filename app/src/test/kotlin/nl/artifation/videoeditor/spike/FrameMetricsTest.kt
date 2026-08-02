package nl.artifation.videoeditor.spike

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests op de meetlat zelf.
 *
 * Als SSIM of de scherptemeting scheef staat, geeft het Spike-scherm een uitslag
 * die nergens op slaat — en dan is er geen manier om dat op het toestel te
 * merken. Vandaar dat ze hier op patronen getoetst worden waarvan de uitkomst
 * vooraf vaststaat.
 */
@RunWith(RobolectricTestRunner::class)
class FrameMetricsTest {

    private fun bitmap(width: Int, height: Int, colourAt: (Int, Int) -> Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = colourAt(x, y)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun checkerboard(width: Int, height: Int, cell: Int = 4) =
        bitmap(width, height) { x, y ->
            if ((x / cell + y / cell) % 2 == 0) Color.WHITE else Color.BLACK
        }

    private fun flat(width: Int, height: Int, colour: Int = Color.GRAY) =
        bitmap(width, height) { _, _ -> colour }

    @Test
    fun `identieke frames geven een SSIM van 1`() {
        val a = checkerboard(64, 64)
        val b = checkerboard(64, 64)

        assertEquals(1.0, FrameMetrics.ssim(a, b), 1e-6)
    }

    @Test
    fun `een geinverteerd frame geeft een lage SSIM`() {
        val a = checkerboard(64, 64)
        val b = bitmap(64, 64) { x, y ->
            if ((x / 4 + y / 4) % 2 == 0) Color.BLACK else Color.WHITE
        }

        assertTrue(
            "zwart-wit omgedraaid hoort ver van 1 te liggen",
            FrameMetrics.ssim(a, b) < 0.2,
        )
    }

    @Test
    fun `een egaal vlak lijkt niet op een schaakbord`() {
        assertTrue(FrameMetrics.ssim(checkerboard(64, 64), flat(64, 64)) < 0.5)
    }

    @Test
    fun `frames van verschillend formaat worden geweigerd`() {
        val fout = runCatching {
            FrameMetrics.ssim(checkerboard(64, 64), checkerboard(32, 32))
        }.exceptionOrNull()

        assertTrue(fout is IllegalArgumentException)
    }

    @Test
    fun `een schaakbord is scherper dan een egaal vlak`() {
        val sharp = FrameMetrics.sharpness(checkerboard(128, 128), 0.5f, 0.5f, 30)
        val blurred = FrameMetrics.sharpness(flat(128, 128), 0.5f, 0.5f, 30)

        assertTrue("scherp $sharp hoort boven vlak $blurred te liggen", sharp > blurred)
        assertEquals(0.0, blurred, 1e-9)
    }

    /**
     * De meting moet lokaal zijn: het Spike-scherm vergelijkt scherpte binnen de
     * cirkel met scherpte erbuiten, en dat werkt alleen als het venster ook
     * werkelijk alleen naar zijn eigen omgeving kijkt.
     */
    @Test
    fun `de scherptemeting kijkt alleen naar zijn eigen venster`() {
        // Links een schaakbord, rechts egaal.
        val split = bitmap(128, 128) { x, y ->
            if (x < 64) {
                if ((x / 4 + y / 4) % 2 == 0) Color.WHITE else Color.BLACK
            } else {
                Color.GRAY
            }
        }

        val left = FrameMetrics.sharpness(split, 0.25f, 0.5f, 20)
        val right = FrameMetrics.sharpness(split, 0.75f, 0.5f, 20)

        assertTrue("links $left hoort veel scherper te zijn dan rechts $right", left > right * 5)
    }

    @Test
    fun `een venster aan de rand levert nog steeds een waarde op`() {
        val value = FrameMetrics.sharpness(checkerboard(64, 64), 0f, 0f, 30)

        assertTrue("een venster dat buiten het beeld valt mag niet omvallen", value >= 0.0)
    }
}
