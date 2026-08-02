package nl.artifation.videoeditor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import nl.artifation.videoeditor.model.CaptionStyle
import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.PlannedClip
import nl.artifation.videoeditor.model.activeIndexAt
import nl.artifation.videoeditor.model.sourcePtsFor

/**
 * Ondertitels als overlay op het beeld.
 *
 * Media3 vraagt per frame om een bitmap. Die elke keer opnieuw tekenen zou dertig
 * keer per seconde een volledig frame aan tekstopmaak kosten voor een regel die
 * seconden blijft staan, dus wordt de laatst getekende cue bewaard. Eén bitmap
 * tegelijk en niet een cache per cue: bij honderd cues zou dat honderd frames aan
 * geheugen zijn, terwijl afspelen toch vooruit gaat en elke cue één keer nodig is.
 *
 * Welke cue wanneer in beeld hoort, wordt niet hier bepaald maar in `:core-model`
 * ([activeIndexAt] en [sourcePtsFor]), waar het getest kan worden zonder toestel.
 * Deze klasse tekent alleen.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README:
 * de apk-job in CI is de eerste plek waar deze code langs een compiler gaat.
 */
@UnstableApi
internal class CaptionOverlay(
    private val cues: List<Cue>,
    private val style: CaptionStyle,
    private val clip: PlannedClip,
    private val frameWidth: Int,
    private val frameHeight: Int,
) : BitmapOverlay() {

    /** Doorzichtig en zo klein mogelijk: dit staat in beeld als er niets te tonen is. */
    private val leeg: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private var laatsteIndex = GEEN_CUE
    private var laatsteBitmap: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        // Cues horen bij de bronclip, dus door dezelfde omrekening als de mask:
        // op een getrimde of versnelde clip lopen ze anders weg van het beeld.
        val index = cues.activeIndexAt(clip.sourcePtsFor(presentationTimeUs))
        if (index == GEEN_CUE) return leeg

        val bewaard = laatsteBitmap
        if (index == laatsteIndex && bewaard != null) return bewaard

        return teken(cues[index]).also {
            laatsteIndex = index
            laatsteBitmap = it
        }
    }

    private fun teken(cue: Cue): Bitmap {
        val bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Alle maten als fractie van de framehoogte, niet in pixels: preview draait
        // op een lagere resolutie dan de export, en pixels zouden daar zichtbaar
        // verschillen. Hoogte en niet breedte, zodat tekst in liggend materiaal
        // niet ineens de halve breedte beslaat.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.fontSizeFrac * frameHeight
            typeface = Typeface.DEFAULT_BOLD
        }

        val tekstBreedte = (frameWidth * TEKST_BREEDTE_FRACTIE).toInt()
        val layout = StaticLayout.Builder
            .obtain(cue.text, 0, cue.text.length, paint, tekstBreedte)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        val links = (frameWidth - tekstBreedte) / 2f
        val boven = frameHeight - style.bottomMarginFrac * frameHeight - layout.height
        canvas.translate(links, boven)

        // Eerst de rand, dan de vulling eroverheen. Andersom vreet de rand de
        // helft van elke letter op.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = style.outlineWidthFrac * frameHeight
        paint.color = style.outlineArgb
        layout.draw(canvas)

        paint.style = Paint.Style.FILL
        paint.color = style.fillArgb
        layout.draw(canvas)

        return bitmap
    }

    private companion object {
        const val GEEN_CUE = -1

        /** Marge links en rechts, zodat tekst niet tegen de rand plakt. */
        const val TEKST_BREEDTE_FRACTIE = 0.86f
    }
}
