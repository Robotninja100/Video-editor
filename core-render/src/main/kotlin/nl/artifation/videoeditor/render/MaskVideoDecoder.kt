package nl.artifation.videoeditor.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.util.GlUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Levert het maskframe dat bij een gevraagd brontijdstip hoort.
 *
 * De maskvideo wordt door een **tweede** [MediaCodec] gedecodeerd, los van de
 * decoder die Media3 voor het bronmateriaal gebruikt, en gerenderd in een
 * [SurfaceTexture] die aan een OES-textuur hangt. Die textuur leest de shader.
 *
 * ### Waarom trekken en niet duwen
 *
 * De renderketen bepaalt het tempo: Media3 levert een bronframe met een
 * presentatietijd, en dáár moet het juiste maskframe bij. Een decoder die zelf
 * frames aanlevert loopt onvermijdelijk voor of achter, en dat is precies de
 * desynchronisatie die bewijs 2 van fase 0 moet uitsluiten. Vandaar [advanceTo]:
 * de shader vraagt, deze klasse levert.
 *
 * ### Waarom nooit seeken
 *
 * De gevraagde tijden lopen monotoon vooruit — de renderketen gaat één kant op.
 * Een seek zou naar het dichtstbijzijnde keyframe springen en daarna opnieuw
 * vooruit moeten decoderen; alleen maar duurder. Bij een getrimde clip (in-punt
 * ≠ 0) betekent dit wel dat de eerste [advanceTo] een stuk vooruit moet spoelen.
 * Dat gebeurt met één textuurupdate in plaats van één per overgeslagen frame,
 * dankzij de vooruitblik van één buffer hieronder.
 *
 * ### De vooruitblik
 *
 * De invariant is: na [advanceTo] bevat de textuur het *nieuwste* maskframe met
 * een presentatietijd ≤ het doel. Om te weten of een frame het nieuwste is moet
 * het volgende bekend zijn — en dat kan pas na het decoderen ervan. Daarom houdt
 * deze klasse steeds één gedecodeerde buffer vast zonder hem te renderen. Zodra
 * de opvolger binnen is, is duidelijk of de vastgehouden buffer getoond
 * (`releaseOutputBuffer(index, true)`) of weggegooid moet worden.
 *
 * ### Draden
 *
 * [createOnGlThread], [advanceTo] en [release] moeten allemaal op de GL-thread van
 * Media3 draaien, met de bijbehorende EGL-context actueel: de OES-textuur en de
 * [SurfaceTexture] horen bij die context, en `updateTexImage()` werkt alleen daar.
 */
internal class MaskVideoDecoder private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val surfaceTexture: SurfaceTexture,
    private val surface: Surface,
    private val callbackThread: HandlerThread,
    /** De OES-textuur waar de shader uit leest. */
    val textureId: Int,
) {

    private val frameLock = ReentrantLock()
    private val frameArrived = frameLock.newCondition()
    private var pendingFrames = 0

    private val bufferInfo = MediaCodec.BufferInfo()

    /** De vastgehouden, nog niet getoonde buffer; zie de klassedoc. */
    private var heldIndex = NO_BUFFER
    private var heldPtsUs = 0L

    private var inputDone = false
    private var outputDone = false

    /** Blijft false tot er ooit een maskframe in de textuur stond. */
    var hasFrame: Boolean = false
        private set

    private val transformMatrix = FloatArray(16)

    init {
        surfaceTexture.setOnFrameAvailableListener(
            {
                frameLock.withLock {
                    pendingFrames++
                    frameArrived.signalAll()
                }
            },
            Handler(callbackThread.looper),
        )
    }

    /**
     * Zorgt dat de textuur het nieuwste maskframe met presentatietijd ≤ [targetUs]
     * bevat. [targetUs] is een **bron**tijd: de shader telt er het in-punt van de
     * clip bij op voordat hij hier komt.
     *
     * Geeft false als er (nog) geen maskframe is; de shader laat het beeld dan
     * ongemoeid in plaats van te gokken.
     */
    fun advanceTo(targetUs: Long): Boolean {
        while (true) {
            if (heldIndex != NO_BUFFER) {
                if (heldPtsUs > targetUs && hasFrame) {
                    // De textuur klopt al: het vastgehouden frame ligt voorbij het
                    // doel, dus wat er staat is het nieuwste dat er niet voorbij ligt.
                    return true
                }

                when (val next = dequeueOutputBuffer()) {
                    null -> {
                        // Einde van de maskvideo: het laatste frame is het beste dat er is.
                        renderHeld()
                        return hasFrame
                    }

                    else -> if (next.ptsUs <= targetUs) {
                        // Er komt nog een frame dat dichter bij het doel ligt; het
                        // vastgehouden frame hoeft dus nooit getoond te worden.
                        codec.releaseOutputBuffer(heldIndex, false)
                        heldIndex = next.index
                        heldPtsUs = next.ptsUs
                    } else {
                        // Het vastgehouden frame is het nieuwste ≤ doel. Tonen, en de
                        // opvolger bewaren voor een volgende aanroep.
                        renderHeld()
                        heldIndex = next.index
                        heldPtsUs = next.ptsUs
                        return hasFrame
                    }
                }
            } else {
                val next = dequeueOutputBuffer() ?: return hasFrame
                heldIndex = next.index
                heldPtsUs = next.ptsUs
            }
        }
    }

    /** De transformatie die [SurfaceTexture] op de textuurcoördinaten wil zien. */
    fun transformMatrix(): FloatArray = transformMatrix

    fun release() {
        if (heldIndex != NO_BUFFER) {
            runCatching { codec.releaseOutputBuffer(heldIndex, false) }
            heldIndex = NO_BUFFER
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        surface.release()
        surfaceTexture.release()
        callbackThread.quitSafely()
        runCatching { GlUtil.deleteTexture(textureId) }
    }

    /**
     * Toont het vastgehouden frame en wacht tot het daadwerkelijk in de
     * [SurfaceTexture] staat voordat het naar de textuur gaat.
     */
    private fun renderHeld() {
        if (heldIndex == NO_BUFFER) return

        codec.releaseOutputBuffer(heldIndex, true)
        heldIndex = NO_BUFFER

        if (awaitFrame()) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
            hasFrame = true
        }
    }

    private fun awaitFrame(): Boolean = frameLock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(FRAME_TIMEOUT_MS)
        while (pendingFrames == 0 && remaining > 0) {
            remaining = frameArrived.awaitNanos(remaining)
        }
        if (pendingFrames == 0) return false
        pendingFrames--
        true
    }

    /** Voedt de decoder tot er een frame uit komt. Null betekent einde stroom. */
    private fun dequeueOutputBuffer(): DecodedFrame? {
        // Een decoder die niets meer teruggeeft mag de GL-thread niet laten hangen.
        // Zonder deze teller draait de lus door als de stroom halverwege stukloopt
        // en er dus nooit een end-of-stream-vlag komt.
        var idleRounds = 0

        while (true) {
            if (outputDone) return null

            feedInput()

            val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (++idleRounds >= MAX_IDLE_ROUNDS) {
                        outputDone = true
                        return null
                    }
                }

                // INFO_OUTPUT_BUFFERS_CHANGED staat hier niet bij: dat wordt sinds
                // API 21 niet meer teruggegeven en minSdk is 26.
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> idleRounds = 0

                index < 0 -> idleRounds = 0

                else -> {
                    idleRounds = 0
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        codec.releaseOutputBuffer(index, false)
                        return null
                    }
                    if (bufferInfo.size == 0) {
                        codec.releaseOutputBuffer(index, false)
                    } else {
                        return DecodedFrame(index, bufferInfo.presentationTimeUs)
                    }
                }
            }
        }
    }

    private fun feedInput() {
        if (inputDone) return

        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return

        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)

        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private data class DecodedFrame(val index: Int, val ptsUs: Long)

    companion object {
        private const val NO_BUFFER = -1
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * Ruim genomen. De GL-thread staat hier te wachten, dus dit is een noodrem
         * tegen vastlopen, geen normale werking.
         */
        private const val FRAME_TIMEOUT_MS = 2_000L

        /** 200 × 10 ms: twee seconden zonder enig frame telt als een dode stroom. */
        private const val MAX_IDLE_ROUNDS = 200

        /**
         * Moet op de GL-thread van Media3 aangeroepen worden: de OES-textuur en de
         * [SurfaceTexture] horen bij de EGL-context die daar actueel is.
         */
        fun createOnGlThread(context: Context, maskUri: String): MaskVideoDecoder {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(maskUri), null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run {
                extractor.release()
                error("maskvideo $maskUri bevat geen videospoor")
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))

            val textureId = GlUtil.createExternalTexture()
            val surfaceTexture = SurfaceTexture(textureId)
            val surface = Surface(surfaceTexture)

            val callbackThread = HandlerThread("mask-decoder").apply { start() }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            codec.start()

            return MaskVideoDecoder(
                extractor = extractor,
                codec = codec,
                surfaceTexture = surfaceTexture,
                surface = surface,
                callbackThread = callbackThread,
                textureId = textureId,
            )
        }
    }
}
