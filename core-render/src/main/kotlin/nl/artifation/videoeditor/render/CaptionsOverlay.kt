package nl.artifation.videoeditor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import nl.artifation.videoeditor.model.CaptionStyle
import nl.artifation.videoeditor.model.Cue
import kotlin.math.max

/**
 * Ondertitels als één overlay die per cue van bitmap wisselt.
 *
 * De voor de hand liggende opzet — één [BitmapOverlay] per cue, met alpha nul
 * buiten zijn venster — zet net zo veel texturen op de GPU als er cues zijn. Bij
 * een video van een paar minuten zijn dat er honderden. Daarom één overlay die de
 * bitmap van de *actieve* cue teruggeeft. [BitmapOverlay] uploadt alleen opnieuw
 * als de bitmap een ander object is, dus dat is één upload per cue-wissel in plaats
 * van per frame.
 *
 * De cues staan al in cliptijd, met trim en snelheid verrekend door
 * `:core-model`. Hier wordt niets meer omgerekend.
 */
internal class CaptionsOverlay(
    private val style: CaptionStyle,
    cues: List<Cue>,
) : BitmapOverlay() {

    /** Gesorteerd, zodat het opzoeken van de actieve cue vooruit kan lopen. */
    private val cues: List<Cue> = cues.sortedBy { it.startUs }

    private var videoSize: Size = Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)

    /** De cue waarvoor [currentBitmap] gemaakt is, of -1 voor "geen". */
    private var currentIndex: Int = UNSET
    private var currentBitmap: Bitmap? = null

    /**
     * Wordt teruggegeven als er geen cue loopt. Eén pixel in plaats van een leeg
     * beeldvullend vlak: dat scheelt een upload van enkele megabytes per gat.
     */
    private val blank: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.TRANSPARENT) }
    }

    override fun configure(videoSize: Size) {
        if (videoSize.width != this.videoSize.width || videoSize.height != this.videoSize.height) {
            this.videoSize = videoSize
            // Het formaat is veranderd, dus wat er gecachet stond klopt niet meer.
            currentIndex = UNSET
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val index = indexOfCueAt(presentationTimeUs)
        if (index == UNSET) return blank

        // Zelfde cue als vorige frame: dezelfde bitmap teruggeven, zodat Media3
        // de textuur niet opnieuw uploadt.
        currentBitmap?.let { if (index == currentIndex) return it }

        val rendered = render(cues[index])
        currentBitmap?.recycle()
        currentBitmap = rendered
        currentIndex = index
        return rendered
    }

    override fun getTextureSize(presentationTimeUs: Long): Size =
        if (indexOfCueAt(presentationTimeUs) == UNSET) {
            Size(1, 1)
        } else {
            videoSize
        }

    override fun release() {
        currentBitmap?.recycle()
        currentBitmap = null
        currentIndex = UNSET
        super.release()
    }

    /** Lineair zoeken mag: er lopen zelden meer dan een paar honderd cues. */
    private fun indexOfCueAt(timeUs: Long): Int =
        cues.indexOfFirst { timeUs >= it.startUs && timeUs < it.endUs }

    /**
     * Tekent één cue beeldvullend, zodat de overlay zonder verdere transformatie
     * over het frame past. De tekst zelf staat onderin, op [CaptionStyle.bottomMarginFrac].
     */
    private fun render(cue: Cue): Bitmap {
        val width = videoSize.width
        val height = videoSize.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Alle maten zijn fracties van de framehoogte, niet pixels: preview draait
        // op een lagere resolutie dan de export en moet er hetzelfde uitzien.
        // Losse verwijzing: binnen een Paint-apply verwijst `style` naar
        // Paint.style, niet naar het veld van deze klasse.
        val captionStyle = style
        val fontSize = captionStyle.fontSizeFrac * height
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            color = captionStyle.fillArgb
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val outline = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, captionStyle.outlineWidthFrac * height)
            color = captionStyle.outlineArgb
        }

        val lines = wrap(cue.text, fill, width * TEXT_WIDTH_FRAC)
        val lineHeight = fontSize * LINE_SPACING
        val baseline = height * (1f - captionStyle.bottomMarginFrac)

        lines.forEachIndexed { index, line ->
            // Van onder naar boven stapelen, zodat de onderste regel op de marge blijft.
            val y = baseline - (lines.size - 1 - index) * lineHeight
            // Contour eerst, vulling erover: anders vreet de streek de letters aan.
            canvas.drawText(line, width / 2f, y, outline)
            canvas.drawText(line, width / 2f, y, fill)
        }

        return bitmap
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        var line = StringBuilder()

        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                lines += line.toString()
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private companion object {
        const val UNSET = -1
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920

        /** Ondertitels lopen niet tot de rand; dat leest slecht op een telefoon. */
        const val TEXT_WIDTH_FRAC = 0.86f
        const val LINE_SPACING = 1.2f
    }
}
