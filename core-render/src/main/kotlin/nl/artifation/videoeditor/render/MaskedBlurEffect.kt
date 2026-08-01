package nl.artifation.videoeditor.render

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

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
    private val context: Context,
    private val maskUri: Uri,
    private val radiusFrac: Float,
    private val clipInPointUs: Long,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MaskedBlurShaderProgram(
            context = context,
            maskUri = maskUri,
            radiusFrac = radiusFrac,
            clipInPointUs = clipInPointUs,
        ) { uri, textureId ->
            MaskVideoDecoder.open(uri, textureId) { target ->
                MediaExtractor().apply { setDataSource(context, target, null) }
            }
        }
}
