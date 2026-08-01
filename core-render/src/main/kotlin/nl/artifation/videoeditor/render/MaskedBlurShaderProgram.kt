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
     * In-point van de clip. De effect-PTS staat in tijdlijntijd; de masktrack
     * hoort bij de bron. Zonder deze correctie loopt de mask uit de pas zodra
     * een clip getrimd is — en dat valt pas op met echt materiaal.
     */
    private val clipInPointUs: Long,
    openDecoder: (Uri, Int) -> MaskVideoDecoder,
) : BaseGlShaderProgram(/* useHdr= */ false, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = GlProgram(context, VERTEX_SHADER, FRAGMENT_SHADER)
    private val maskTextureId: Int = GlUtil.createExternalTexture()
    private val decoder: MaskVideoDecoder = openDecoder(maskUri, maskTextureId)

    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // Tijdlijn-PTS naar bron-PTS, daarna de mask vooruittrekken.
            decoder.advanceTo(presentationTimeUs + clipInPointUs)

            program.use()
            program.setSamplerTexIdUniform("uSource", inputTexId, /* texUnitIndex= */ 0)
            program.setSamplerTexIdUniform("uMask", maskTextureId, /* texUnitIndex= */ 1)
            program.setFloatUniform("uRadius", radiusFrac)
            program.setFloatsUniform("uTexelSize", floatArrayOf(1f / width, 1f / height))
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
        super.release()
        decoder.release()
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
            uniform vec2 uTexelSize;
            varying vec2 vTex;

            vec4 blur(vec2 uv) {
              // 13-taps separabele benadering; radius in fractie van de breedte,
              // zodat preview en export hetzelfde beeld geven.
              float step = uRadius;
              vec4 sum = vec4(0.0);
              float total = 0.0;
              for (int i = -6; i <= 6; i++) {
                float offset = float(i) / 6.0 * step;
                float weight = 1.0 - abs(float(i)) / 7.0;
                sum += texture2D(uSource, uv + vec2(offset, 0.0)) * weight;
                sum += texture2D(uSource, uv + vec2(0.0, offset)) * weight;
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
