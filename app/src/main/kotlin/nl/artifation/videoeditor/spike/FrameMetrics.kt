package nl.artifation.videoeditor.spike

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * De metingen waar de fase 0-poort op afgaat.
 *
 * Allemaal op grijswaarden: helderheid draagt de structuur, en chroma zou de
 * uitkomst alleen maar ruisiger maken zonder er iets aan toe te voegen.
 */
internal object FrameMetrics {

    /**
     * Structurele gelijkenis tussen twee frames, van 0 (niets gemeen) tot 1 (gelijk).
     *
     * De gebruikelijke variant met vensters van 8×8 in plaats van één getal over het
     * hele beeld: pariteit die op de helft van het frame misgaat maar gemiddeld
     * klopt, is geen pariteit.
     */
    fun ssim(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height) {
            "frames van verschillend formaat: ${a.width}×${a.height} en ${b.width}×${b.height}"
        }

        val width = a.width
        val height = a.height
        val lumaA = luma(a)
        val lumaB = luma(b)

        // De constanten uit het oorspronkelijke SSIM-artikel, voor 8 bits per kanaal.
        val c1 = (0.01 * 255.0) * (0.01 * 255.0)
        val c2 = (0.03 * 255.0) * (0.03 * 255.0)

        var total = 0.0
        var windows = 0

        var wy = 0
        while (wy + WINDOW <= height) {
            var wx = 0
            while (wx + WINDOW <= width) {
                var sumA = 0.0
                var sumB = 0.0
                var sumAA = 0.0
                var sumBB = 0.0
                var sumAB = 0.0

                for (y in wy until wy + WINDOW) {
                    for (x in wx until wx + WINDOW) {
                        val va = lumaA[y * width + x].toDouble()
                        val vb = lumaB[y * width + x].toDouble()
                        sumA += va
                        sumB += vb
                        sumAA += va * va
                        sumBB += vb * vb
                        sumAB += va * vb
                    }
                }

                val n = (WINDOW * WINDOW).toDouble()
                val meanA = sumA / n
                val meanB = sumB / n
                val varA = sumAA / n - meanA * meanA
                val varB = sumBB / n - meanB * meanB
                val covAB = sumAB / n - meanA * meanB

                val numerator = (2 * meanA * meanB + c1) * (2 * covAB + c2)
                val denominator = (meanA * meanA + meanB * meanB + c1) * (varA + varB + c2)

                total += numerator / denominator
                windows++
                wx += WINDOW
            }
            wy += WINDOW
        }

        return if (windows == 0) 0.0 else total / windows
    }

    /**
     * Hoe scherp het beeld is binnen een vierkant rond ([centerX], [centerY]).
     *
     * Gemeten als de gemiddelde absolute Laplaciaan: die reageert op abrupte
     * overgangen, en dat is precies wat een blur wegneemt. De absolute waarde is
     * niet betekenisvol, de verhouding tussen twee gebieden wel — daarom wordt hij
     * altijd als ratio gebruikt en nooit als drempel op zich.
     */
    fun sharpness(bitmap: Bitmap, centerX: Float, centerY: Float, halfSize: Int): Double {
        val width = bitmap.width
        val height = bitmap.height
        val luma = luma(bitmap)

        val cx = (centerX * width).roundToInt()
        val cy = (centerY * height).roundToInt()

        // Eén pixel marge, want de Laplaciaan kijkt naar de buren.
        val left = (cx - halfSize).coerceAtLeast(1)
        val right = (cx + halfSize).coerceAtMost(width - 2)
        val top = (cy - halfSize).coerceAtLeast(1)
        val bottom = (cy + halfSize).coerceAtMost(height - 2)

        if (right <= left || bottom <= top) return 0.0

        var total = 0.0
        var count = 0

        for (y in top..bottom) {
            for (x in left..right) {
                val center = luma[y * width + x] * 4
                val neighbours = luma[y * width + x - 1] +
                    luma[y * width + x + 1] +
                    luma[(y - 1) * width + x] +
                    luma[(y + 1) * width + x]
                total += abs(center - neighbours).toDouble()
                count++
            }
        }

        return if (count == 0) 0.0 else total / count
    }

    private fun luma(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val out = IntArray(width * height)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            // Rec. 601 luma, in gehele getallen zodat de meting reproduceerbaar is.
            out[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return out
    }

    private const val WINDOW = 8
}
