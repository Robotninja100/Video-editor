package nl.artifation.videoeditor.render

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import nl.artifation.videoeditor.model.PlannedClip

/**
 * Fabriek voor [MaskedBlurShaderProgram].
 *
 * Media3 verwacht een `GlEffect` in de effectketen; die maakt per renderpas een
 * shaderprogramma aan. Omdat er een eigen decoder in hangt, wordt hier ook de
 * `MediaExtractor` aangemaakt — één per pas, want preview en export draaien
 * onafhankelijk van elkaar en mogen geen decoder delen.
 *
 * **Niet gecompileerd.** Zie het bouwplan, fase 0.
 */
@UnstableApi
internal class MaskedBlurEffect(
    private val maskUri: Uri,
    private val radiusFrac: Float,
    private val clip: PlannedClip,
) : GlEffect {

    /**
     * De `Context` komt van Media3 bij elke pas mee; er stond er ook een in de
     * constructor, die nooit gebruikt werd omdat de parameter hem afdekte.
     */
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MaskedBlurShaderProgram(
            maskUri = maskUri,
            radiusFrac = radiusFrac,
            clip = clip,
        ) { uri, textureId ->
            MaskVideoDecoder.open(uri, textureId) { target ->
                MediaExtractor().apply { setDataSource(context, target, null) }
            }
        }
}
