package nl.artifation.videoeditor.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Maakt het testmateriaal voor de spike op het toestel zelf.
 *
 * Het bouwplan ging ervan uit dat de bronvideo en de maskvideo met ffmpeg op een
 * werkplek gemaakt zouden worden. Dat is een afhankelijkheid die niemand hier
 * heeft — niet op de telefoon, en in een bouwomgeving zonder toestel al helemaal
 * niet. Alles wat nodig is zit al in Android: tekenen met [Canvas], encoderen met
 * [MediaCodec], wegschrijven met [MediaMuxer].
 *
 * Bewust in YUV via [MediaCodec.getInputImage] en niet via een invoer-Surface: dat
 * scheelt een complete EGL-opzet, en voor een paar honderd frames is de
 * softwareconversie snel genoeg.
 */
internal object TestMediaFactory {

    const val WIDTH: Int = 720
    const val HEIGHT: Int = 1280
    const val FRAME_RATE: Int = 30
    const val DURATION_US: Long = 10_000_000L

    /** Het mask staat op halve resolutie, zoals fase 6 het zou opleveren. */
    const val MASK_WIDTH: Int = WIDTH / 2
    const val MASK_HEIGHT: Int = HEIGHT / 2

    /** Straal van de cirkel, als fractie van de breedte. */
    private const val CIRCLE_RADIUS_FRAC = 0.18f

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /**
     * Waar de cirkel op tijdstip [timeUs] staat, in genormaliseerde coördinaten.
     *
     * Deze formule is de enige waarheid over de beweging: hij tekent het mask én
     * hij bepaalt waar de controle straks scherpte verwacht. Zonder dat gedeelde
     * ijkpunt zou bewijs 2 alleen "er is iets scherp" kunnen vaststellen in plaats
     * van "het juiste stuk is scherp op het juiste moment".
     */
    fun circleCenterAt(timeUs: Long): Pair<Float, Float> {
        val phase = 2.0 * PI * timeUs.toDouble() / DURATION_US.toDouble()
        // Een liggende acht: beide assen bewegen, met verschillende periodes, zodat
        // een fout in de tijd niet toevallig op dezelfde plek uitkomt.
        val x = 0.5f + 0.28f * sin(phase).toFloat()
        val y = 0.5f + 0.22f * sin(2.0 * phase).toFloat()
        return x to y
    }

    /**
     * Bronvideo met veel fijne details, want een blur is alleen meetbaar op iets
     * wat scherp kán zijn. Een egaal vlak blijft er na blurren hetzelfde uitzien.
     */
    fun createSourceVideo(target: File): File = encode(target, WIDTH, HEIGHT) { canvas, timeUs ->
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = WIDTH / 24f

        // Schaakbord: hoog contrast op korte afstand, dus maximaal gevoelig voor blur.
        for (row in 0..(HEIGHT / cell).toInt()) {
            for (col in 0..(WIDTH / cell).toInt()) {
                if ((row + col) % 2 == 0) continue
                paint.color = Color.WHITE
                canvas.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, paint)
            }
        }

        // Gekleurde ringen eroverheen, zodat ook chroma iets te vergelijken heeft.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = cell / 3f
        val progress = timeUs.toFloat() / DURATION_US
        for (ring in 0 until 6) {
            paint.color = Color.HSVToColor(floatArrayOf((ring * 60f + progress * 360f) % 360f, 1f, 1f))
            canvas.drawCircle(WIDTH / 2f, HEIGHT / 2f, cell * (2 + ring * 2), paint)
        }
        paint.style = Paint.Style.FILL

        // Een meelopend blokje: bewijst dat de frames in de goede volgorde staan.
        val (cx, _) = circleCenterAt(timeUs)
        paint.color = Color.YELLOW
        canvas.drawRect(cx * WIDTH - cell, 0f, cx * WIDTH + cell, cell, paint)
    }

    /**
     * Maskvideo: witte cirkel op zwart, in grijswaarden.
     *
     * Wit is scherp, zwart is geblurd — dezelfde afspraak als in de shader. De
     * cirkel volgt [circleCenterAt], zodat de controle weet waar hij moet kijken.
     */
    fun createMaskVideo(target: File): File =
        encode(target, MASK_WIDTH, MASK_HEIGHT) { canvas, timeUs ->
            canvas.drawColor(Color.BLACK)

            val (cx, cy) = circleCenterAt(timeUs)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawCircle(
                cx * MASK_WIDTH,
                cy * MASK_HEIGHT,
                CIRCLE_RADIUS_FRAC * MASK_WIDTH,
                paint,
            )
        }

    /** Straal van de cirkel in pixels van een frame van [frameWidth] breed. */
    fun circleRadiusPx(frameWidth: Int): Float = CIRCLE_RADIUS_FRAC * frameWidth

    private fun encode(
        target: File,
        width: Int,
        height: Int,
        draw: (Canvas, Long) -> Unit,
    ): File {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            // Ruim bemeten: compressieartefacten zouden de SSIM-meting vertroebelen
            // en dan meet de spike de encoder in plaats van de renderketen.
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 12)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            // Elke seconde een keyframe: de maskdecoder leest alleen vooruit, maar
            // MediaMetadataRetriever moet er wel op kunnen springen.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)

        val frameCount = (DURATION_US * FRAME_RATE / 1_000_000L).toInt()
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var frame = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        if (frame >= frameCount) {
                            codec.queueInputBuffer(
                                index, 0, 0, frameTimeUs(frameCount),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val timeUs = frameTimeUs(frame)
                            draw(canvas, timeUs)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                            val image = codec.getInputImage(index)
                            val size = if (image != null) {
                                writeYuv(image, pixels, width, height)
                                codec.getInputBuffer(index)?.capacity() ?: (width * height * 3 / 2)
                            } else {
                                width * height * 3 / 2
                            }

                            codec.queueInputBuffer(index, 0, size, timeUs, 0)
                            frame++
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "het uitvoerformaat mag maar een keer wijzigen" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        // De codecconfiguratie hoort in het formaat, niet in de stroom.
                        val isConfig =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                        if (buffer != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        }

                        codec.releaseOutputBuffer(index, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            bitmap.recycle()
        }

        return target
    }

    private fun frameTimeUs(frame: Int): Long = frame.toLong() * 1_000_000L / FRAME_RATE

    /**
     * Schrijft ARGB-pixels als YUV 4:2:0 in het [android.media.Image] van de encoder.
     *
     * Via de Image-API en niet via een vaste bufferindeling, omdat elke encoder zijn
     * eigen rij- en pixelafstanden hanteert. Zo werkt dit op elk toestel zonder per
     * kleurformaat een aparte tak.
     */
    private fun writeYuv(image: android.media.Image, pixels: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (y in 0 until height) {
            val yRow = y * yPlane.rowStride
            for (x in 0 until width) {
                val argb = pixels[y * width + x]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                // BT.601, beperkt bereik (16..235). Dat is wat H.264 zonder expliciete
                // markering betekent, en waar de decoder straks van uitgaat.
                val luma = (16 + (65.481 * r + 128.553 * g + 24.966 * b) / 255.0)
                yBuffer.put(yRow + x * yPlane.pixelStride, luma.roundToInt().coerceIn(0, 255).toByte())

                // Chroma op halve resolutie: alleen de even pixels bemonsteren.
                if (y % 2 == 0 && x % 2 == 0) {
                    val cb = 128 + (-37.797 * r - 74.203 * g + 112.0 * b) / 255.0
                    val cr = 128 + (112.0 * r - 93.786 * g - 18.214 * b) / 255.0
                    val chromaIndex = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
                    uBuffer.put(chromaIndex, cb.roundToInt().coerceIn(0, 255).toByte())
                    vBuffer.put(
                        (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride,
                        cr.roundToInt().coerceIn(0, 255).toByte(),
                    )
                }
            }
        }
    }

    private const val TIMEOUT_US = 10_000L
}
