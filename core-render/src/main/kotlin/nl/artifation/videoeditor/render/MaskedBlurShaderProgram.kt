package nl.artifation.videoeditor.render

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import nl.artifation.videoeditor.model.RenderEffect

/**
 * Scherp binnen het mask, geblurd erbuiten.
 *
 * Twee passes, want een gaussiaanse blur is separabel: horizontaal naar een eigen
 * tussentextuur, daarna verticaal én samenstellen in één keer naar de uitvoer. Dat
 * scheelt een derde pass, omdat het mengen met het scherpe beeld toch al een
 * sampler nodig heeft.
 *
 * Het maskframe komt uit [MaskVideoDecoder]. Welk maskframe bij welk uitvoerframe
 * hoort, wordt hier **niet** uitgerekend: dat doet [RenderEffect.MaskedBlur.sourcePtsFor]
 * in `:core-model`, met tests eromheen. Twee termen zitten in die omrekening — het
 * in-punt van een getrimde clip en de snelheid — en beide zijn eerder fout gegaan.
 * Ze horen niet thuis in een shader die alleen op een toestel te toetsen is.
 *
 * **Maskconventie: wit is scherp, zwart is geblurd.** Eén keer vastgelegd, hier en
 * in `docs/PRODUCTPLAN.md`, omdat de twee eerdere implementaties het tegengesteld
 * deden en dat pas op een toestel opvalt.
 */
@UnstableApi
internal class MaskedBlurShaderProgram(
    private val context: Context,
    private val effect: RenderEffect.MaskedBlur,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private var blurProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null
    private var decoder: MaskVideoDecoder? = null

    private var width = 0
    private var height = 0

    /** Tussenresultaat van de horizontale pass. */
    private var intermediateTexId = NO_GL_OBJECT
    private var intermediateFboId = NO_GL_OBJECT

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != width || inputHeight != height) {
            releaseIntermediate()
            width = inputWidth
            height = inputHeight
            intermediateTexId = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents= */ false)
            intermediateFboId = GlUtil.createFboForTexture(intermediateTexId)
        }

        if (blurProgram == null) {
            blurProgram = GlProgram(VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
            compositeProgram = GlProgram(VERTEX_SHADER, COMPOSITE_FRAGMENT_SHADER)
        }

        // Hier, en niet in de constructor: de OES-textuur en de SurfaceTexture horen
        // bij de EGL-context die op deze thread actueel is. configure() en
        // drawFrame() draaien allebei op de GL-thread van Media3, de constructor niet.
        if (decoder == null) {
            decoder = MaskVideoDecoder.createOnGlThread(context, effect.maskUri)
        }

        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            drawMaskedBlur(inputTexId, presentationTimeUs)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } catch (e: RuntimeException) {
            // De decoder is geen GL: een MediaCodec die omvalt gooit
            // IllegalStateException. Die hoort ook als frameverwerkingsfout naar
            // buiten te komen, zodat de export netjes faalt in plaats van de app
            // mee te nemen.
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun drawMaskedBlur(inputTexId: Int, presentationTimeUs: Long) {
        val blur = blurProgram ?: return
        val composite = compositeProgram ?: return
        val mask = decoder

        // Media3 heeft de uitvoer-FBO al gebonden voordat drawFrame wordt
        // aangeroepen. Die binding is nergens op te vragen, dus even onthouden
        // voordat pass 1 naar de tussentextuur schrijft.
        val outputFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFbo, 0)

        // Cliptijd naar bronpositie — in-punt én snelheid — en dan de mask
        // vooruittrekken. De omrekening staat in `:core-model`.
        val hasMask = mask?.advanceTo(effect.sourcePtsFor(presentationTimeUs)) ?: false

        // De blurradius is een fractie van de breedte, niet een aantal pixels: preview
        // draait op een lagere resolutie dan de export en moet er hetzelfde uitzien.
        val radiusPx = effect.radiusFrac * width
        val stepX = radiusPx / TAP_SPAN / width
        val stepY = radiusPx / TAP_SPAN / height

        // Pass 1 — horizontaal, naar de tussentextuur.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboId)
        GLES20.glViewport(0, 0, width, height)
        blur.use()
        blur.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
        blur.setFloatUniform("uStepX", stepX)
        blur.setFloatUniform("uStepY", 0f)
        blur.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        blur.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Pass 2 — verticaal én mengen, terug naar de uitvoer die Media3 gaf.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo[0])
        GLES20.glViewport(0, 0, width, height)
        composite.use()
        composite.setSamplerTexIdUniform("uBlurredSampler", intermediateTexId, /* texUnitIndex= */ 0)
        composite.setSamplerTexIdUniform("uSharpSampler", inputTexId, /* texUnitIndex= */ 1)
        composite.setSamplerTexIdUniform(
            "uMaskSampler",
            mask?.textureId ?: 0,
            /* texUnitIndex= */ 2,
        )
        composite.setFloatUniform("uStepX", 0f)
        composite.setFloatUniform("uStepY", stepY)
        composite.setFloatsUniform("uMaskTransform", mask?.transformMatrix() ?: IDENTITY)
        // Zonder maskframe blijft het beeld ongemoeid. Half blurren op goed geluk
        // zou de spike laten slagen op iets wat niet klopt.
        composite.setFloatUniform("uMaskAvailable", if (hasMask) 1f else 0f)
        composite.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        composite.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
        runCatching { decoder?.release() }
        decoder = null
        runCatching { blurProgram?.delete() }
        runCatching { compositeProgram?.delete() }
        blurProgram = null
        compositeProgram = null
        releaseIntermediate()
    }

    private fun releaseIntermediate() {
        if (intermediateFboId != NO_GL_OBJECT) {
            runCatching { GlUtil.deleteFbo(intermediateFboId) }
            intermediateFboId = NO_GL_OBJECT
        }
        if (intermediateTexId != NO_GL_OBJECT) {
            runCatching { GlUtil.deleteTexture(intermediateTexId) }
            intermediateTexId = NO_GL_OBJECT
        }
    }

    private companion object {
        /** Nul is in OpenGL "geen object"; textuur- en FBO-namen beginnen bij 1. */
        const val NO_GL_OBJECT = 0

        /** Halve breedte van de kernel in taps; 4 weerszijden plus het midden is 9. */
        const val TAP_SPAN = 4f

        val IDENTITY: FloatArray = GlUtil.create4x4IdentityMatrix()

        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              // Van genormaliseerde apparaatcoördinaten (-1..1) naar textuur (0..1).
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        /**
         * Negen taps met gaussiaanse gewichten. Ze staan hier als constanten en niet
         * als uniform-array omdat GlProgram alleen losse uniforms betrouwbaar bindt.
         * De gewichten tellen op tot 1, zodat de helderheid niet verloopt.
         */
        const val GAUSSIAN_WEIGHTS = """
            const float W0 = 0.2270270270;
            const float W1 = 0.1945945946;
            const float W2 = 0.1216216216;
            const float W3 = 0.0540540541;
            const float W4 = 0.0162162162;
        """

        const val BLUR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform float uStepX;
            uniform float uStepY;
            varying vec2 vTexCoord;
            $GAUSSIAN_WEIGHTS
            void main() {
              vec2 step = vec2(uStepX, uStepY);
              vec4 sum = texture2D(uTexSampler, vTexCoord) * W0;
              sum += (texture2D(uTexSampler, vTexCoord + step) +
                      texture2D(uTexSampler, vTexCoord - step)) * W1;
              sum += (texture2D(uTexSampler, vTexCoord + step * 2.0) +
                      texture2D(uTexSampler, vTexCoord - step * 2.0)) * W2;
              sum += (texture2D(uTexSampler, vTexCoord + step * 3.0) +
                      texture2D(uTexSampler, vTexCoord - step * 3.0)) * W3;
              sum += (texture2D(uTexSampler, vTexCoord + step * 4.0) +
                      texture2D(uTexSampler, vTexCoord - step * 4.0)) * W4;
              gl_FragColor = sum;
            }
        """

        const val COMPOSITE_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform sampler2D uBlurredSampler;
            uniform sampler2D uSharpSampler;
            uniform samplerExternalOES uMaskSampler;
            uniform mat4 uMaskTransform;
            uniform float uStepX;
            uniform float uStepY;
            uniform float uMaskAvailable;
            varying vec2 vTexCoord;
            $GAUSSIAN_WEIGHTS
            void main() {
              vec4 sharp = texture2D(uSharpSampler, vTexCoord);
              if (uMaskAvailable < 0.5) {
                gl_FragColor = sharp;
                return;
              }

              vec2 step = vec2(uStepX, uStepY);
              vec4 blurred = texture2D(uBlurredSampler, vTexCoord) * W0;
              blurred += (texture2D(uBlurredSampler, vTexCoord + step) +
                          texture2D(uBlurredSampler, vTexCoord - step)) * W1;
              blurred += (texture2D(uBlurredSampler, vTexCoord + step * 2.0) +
                          texture2D(uBlurredSampler, vTexCoord - step * 2.0)) * W2;
              blurred += (texture2D(uBlurredSampler, vTexCoord + step * 3.0) +
                          texture2D(uBlurredSampler, vTexCoord - step * 3.0)) * W3;
              blurred += (texture2D(uBlurredSampler, vTexCoord + step * 4.0) +
                          texture2D(uBlurredSampler, vTexCoord - step * 4.0)) * W4;

              // De SurfaceTexture bepaalt zelf hoe zijn inhoud georiënteerd is;
              // zonder deze matrix staat het mask op sommige toestellen gespiegeld.
              vec2 maskCoord = (uMaskTransform * vec4(vTexCoord, 0.0, 1.0)).xy;
              // Grijswaarden: r, g en b zijn gelijk, dus één kanaal volstaat.
              float m = texture2D(uMaskSampler, maskCoord).r;

              // Wit in het mask is scherp, zwart is geblurd.
              gl_FragColor = mix(blurred, sharp, m);
            }
        """
    }
}

/** Het effect dat [MaskedBlurShaderProgram] in de renderketen hangt. */
@UnstableApi
internal class MaskedBlurEffect(
    private val effect: RenderEffect.MaskedBlur,
    private val effectContext: Context,
) : GlEffect {

    @Throws(VideoFrameProcessingException::class)
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): MaskedBlurShaderProgram =
        MaskedBlurShaderProgram(
            // De context uit het renderplan, niet die van de aanroeper: de
            // maskvideo wordt via een content-uri opgelost en heeft dezelfde
            // rechten nodig als waarmee hij gekozen is.
            context = effectContext,
            effect = effect,
            useHdr = useHdr,
        )

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = effect.radiusFrac <= 0f
}
