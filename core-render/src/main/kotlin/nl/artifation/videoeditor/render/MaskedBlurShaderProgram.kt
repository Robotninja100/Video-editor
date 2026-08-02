package nl.artifation.videoeditor.render

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import nl.artifation.videoeditor.model.PlannedClip
import nl.artifation.videoeditor.model.sourcePtsFor

/**
 * Blur buiten de mask, scherp erbinnen.
 *
 * Twee texturen, één `mix()`. Het effect zelf is klein; de kunst zit in de
 * synchronisatie, die in [MaskVideoDecoder] staat.
 *
 * De blur-straal is een **fractie van de framebreedte**, geen pixelmaat.
 * `CompositionPlayer` speelt vaak op lagere resolutie af dan er geëxporteerd
 * wordt, en een vaste pixelradius ziet er dan in preview anders uit dan in de
 * export — precies het soort verschil waar je dagen naar zoekt.
 *
 * **Niet gecompileerd of gedraaid** — geen Android SDK beschikbaar in de
 * omgeving waarin dit geschreven is.
 */
@UnstableApi
internal class MaskedBlurShaderProgram(
    context: Context,
    maskUri: Uri,
    private val radiusFrac: Float,
    /**
     * De clip waar dit effect bij hoort.
     *
     * Nodig voor de omrekening van uitvoertijd naar bronpositie: die telt de
     * in-point erbij én rekent de snelheid mee. Beide stonden hier eerder los of
     * ontbraken, en `sourcePtsFor` legt de aanname nu op één geteste plek vast.
     */
    private val clip: PlannedClip,
    openDecoder: (Uri, Int) -> MaskVideoDecoder,
) : BaseGlShaderProgram(/* useHdr= */ false, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = GlProgram(context, VERTEX_SHADER, FRAGMENT_SHADER)
    private val maskTextureId: Int = GlUtil.createExternalTexture()

    /**
     * Gooit het openen van de decoder, dan lekken het programma en de textuur
     * hierboven: de constructor keert nooit terug, dus `release()` wordt nooit
     * bereikt. En `toGlShaderProgram` draait per renderpas, dus opnieuw proberen
     * put de decoders uit.
     */
    private val decoder: MaskVideoDecoder = try {
        openDecoder(maskUri, maskTextureId)
    } catch (e: Throwable) {
        runCatching { program.delete() }
        runCatching { GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0) }
        throw e
    }

    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // Uitvoertijd naar bronpositie — in-point én snelheid — en dan de
            // mask vooruittrekken. Zie `sourcePtsFor` in `:core-model`.
            decoder.advanceTo(clip.sourcePtsFor(presentationTimeUs))

            program.use()
            program.setSamplerTexIdUniform("uSource", inputTexId, /* texUnitIndex= */ 0)
            program.setSamplerTexIdUniform("uMask", maskTextureId, /* texUnitIndex= */ 1)
            program.setFloatUniform("uRadius", radiusFrac)
            // Beeldverhouding, niet texelgrootte. `uRadius` is een fractie van de
            // frame*breedte*; zonder correctie is de blur in UV-ruimte even breed
            // als hoog en dus in pixels uitgerekt. En een uniform die de shader
            // niet leest, bestaat na het compileren niet meer — `setFloatsUniform`
            // deed daar `checkNotNull` op, wat elke render op frame 0 sloopte.
            program.setFloatUniform("uAspect", width.toFloat() / height.toFloat())
            program.setBufferAttribute(
                "aPosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            program.bindAttributesAndUniforms()

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        // `super.release()` mag gooien — een verloren EGL-context bij het naar de
        // achtergrond gaan tijdens export. Zonder `finally` bleef de decoder dan
        // hangen met zijn MediaCodec, Surface en SurfaceTexture, voor de rest van
        // de levensduur van het proces.
        try {
            super.release()
        } finally {
            releaseOwnResources()
        }
    }

    private fun releaseOwnResources() {
        runCatching { decoder.release() }
        runCatching { program.delete() }
        runCatching { GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0) }
    }

    private companion object {

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            varying vec2 vTex;
            void main() {
              gl_Position = aPosition;
              vTex = aPosition.xy * 0.5 + 0.5;
            }
        """

        /**
         * De mask is als gewone YUV geëncodeerd met vlakke chroma, dus na de
         * sampler staat de maskwaarde in `.r`.
         *
         * `smoothstep` op de maskrand geeft gratis feathering: de mask staat op
         * halve resolutie en wordt lineair gefilterd, dus de overgang is al zacht.
         */
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            uniform sampler2D uSource;
            uniform samplerExternalOES uMask;
            uniform float uRadius;
            uniform float uAspect;
            varying vec2 vTex;

            vec4 blur(vec2 uv) {
              // 13-taps separabele benadering; radius in fractie van de breedte,
              // zodat preview en export hetzelfde beeld geven.
              vec4 sum = vec4(0.0);
              float total = 0.0;
              for (int i = -6; i <= 6; i++) {
                float t = float(i) / 6.0 * uRadius;
                float weight = 1.0 - abs(float(i)) / 7.0;
                // Verticaal maal de beeldverhouding: uRadius is een fractie van
                // de breedte, dus in UV-ruimte is dezelfde pixelafstand
                // verticaal `uAspect` keer zo groot.
                sum += texture2D(uSource, uv + vec2(t, 0.0)) * weight;
                sum += texture2D(uSource, uv + vec2(0.0, t * uAspect)) * weight;
                total += weight * 2.0;
              }
              return sum / total;
            }

            void main() {
              vec4 sharp = texture2D(uSource, vTex);
              vec4 soft = blur(vTex);
              float m = texture2D(uMask, vTex).r;
              gl_FragColor = mix(sharp, soft, smoothstep(0.35, 0.65, m));
            }
        """
    }
}
