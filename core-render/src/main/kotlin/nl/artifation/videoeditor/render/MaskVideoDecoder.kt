package nl.artifation.videoeditor.render

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Decodeert de masktrack synchroon met de hoofdvideo.
 *
 * Dit is het kernstuk van het hele project, en het bestaat omdat Media3 geen
 * ingang biedt: `VideoCompositor` is publiek en timestamp-gesynchroniseerd, maar
 * `MultipleInputVideoGraph` is `final` en hardcodeert `DefaultVideoCompositor`.
 * Een tweede stream in dezelfde shader krijgen kan dus alleen door hem zelf te
 * decoderen.
 *
 * De oplossing is **pull-based**: `GlShaderProgram.queueInputFrame()` levert de
 * presentation timestamp van het bronframe mee, en deze decoder wordt vooruit
 * getrokken tot zijn eigen PTS die timestamp haalt. Dat werkt in beide
 * timingregimes — `CompositionPlayer` speelt realtime af, `Transformer`
 * exporteert zo snel als de encoder aankan — omdat er nergens naar de wandklok
 * gekeken wordt.
 *
 * Er wordt nooit geseekt: mask en bron delen dezelfde tijdbasis en de
 * afspeelrichting is monotoon, dus vooruitspoelen volstaat.
 *
 * **Niet gecompileerd of gedraaid.** Er was in deze omgeving geen Android SDK
 * beschikbaar. Dit is fase 0 uit het bouwplan en moet op een toestel bewezen
 * worden vóór er verder gebouwd wordt.
 */
internal class MaskVideoDecoder(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val surfaceTexture: SurfaceTexture,
    private val surface: Surface,
    /** De OES-textuur waar `updateTexImage()` naartoe schrijft. */
    val textureId: Int,
) {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    /** PTS van het frame dat op dit moment in de textuur staat. */
    var currentPtsUs: Long = -1L
        private set

    private var frameAvailable = false
    private val frameLock = Object()

    init {
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }
    }

    /**
     * Trekt de mask vooruit tot zijn PTS [targetUs] bereikt of voorbijgaat.
     *
     * Moet aangeroepen worden op de GL-thread met de actieve EGL-context —
     * `updateTexImage()` bindt aan de context van de aanroepende thread. Bij
     * Transformer is dat de thread waarop `drawFrame` draait.
     */
    fun advanceTo(targetUs: Long) {
        while (currentPtsUs < targetUs && !outputDone) {
            feedInput()
            if (!drainOutput()) break
        }
    }

    private fun feedInput() {
        if (inputDone) return

        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return

        val buffer: ByteBuffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)

        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** @return false als er geen voortgang meer te maken is. */
    private fun drainOutput(): Boolean {
        val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)

        return when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> !inputDone
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> true
            index < 0 -> true

            else -> {
                val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hasContent = bufferInfo.size > 0

                // render = true stuurt het frame naar de SurfaceTexture.
                codec.releaseOutputBuffer(index, hasContent)

                if (hasContent) {
                    awaitFrame()
                    surfaceTexture.updateTexImage()
                    currentPtsUs = bufferInfo.presentationTimeUs
                }
                if (endOfStream) outputDone = true
                true
            }
        }
    }

    private fun awaitFrame() {
        synchronized(frameLock) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_WAIT_MS)
            while (!frameAvailable) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                frameLock.wait(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
            }
            frameAvailable = false
        }
    }

    fun release() {
        // Alles apart: gooit er één, dan moeten de andere alsnog vrijkomen.
        // Surface en SurfaceTexture stonden hier kaal, dus een fout in de eerste
        // liet de tweede voorgoed hangen.
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val FRAME_WAIT_MS = 500L

        /**
         * Opent de masktrack en configureert de decoder om naar [textureId] te
         * renderen.
         *
         * Alles wat onderweg wordt aangemaakt, wordt bij een fout weer
         * vrijgegeven. Zonder dat lekt een maskbestand zonder videotrack de
         * `MediaExtractor` (een native bestandsdescriptor), en een geweigerde
         * `configure` — onbekend profiel, of alle hardware-decoders al bezet —
         * lekt daarnaast de `SurfaceTexture`, de `Surface` en de `MediaCodec`.
         * Deze functie draait per renderpas, dus opnieuw proberen put de
         * decoders anders uit.
         */
        fun open(uri: Uri, textureId: Int, openExtractor: (Uri) -> MediaExtractor): MaskVideoDecoder {
            var extractor: MediaExtractor? = null
            var surfaceTexture: SurfaceTexture? = null
            var surface: Surface? = null
            var codec: MediaCodec? = null

            try {
                extractor = openExtractor(uri)

                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: error("maskbestand $uri heeft geen videotrack")
                extractor.selectTrack(trackIndex)

                val format = extractor.getTrackFormat(trackIndex)
                surfaceTexture = SurfaceTexture(textureId)
                surface = Surface(surfaceTexture)

                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, surface, null, 0)
                codec.start()

                return MaskVideoDecoder(extractor, codec, surfaceTexture, surface, textureId)
            } catch (e: Throwable) {
                runCatching { codec?.release() }
                runCatching { surface?.release() }
                runCatching { surfaceTexture?.release() }
                runCatching { extractor?.release() }
                throw e
            }
        }
    }
}
