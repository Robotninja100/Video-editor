package nl.artifation.videoeditor.spike

import android.content.Context
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Frames uit een geëxporteerd bestand.
 *
 * `OPTION_CLOSEST` en niet `OPTION_CLOSEST_SYNC`: die laatste springt naar het
 * dichtstbijzijnde keyframe en levert dan een frame van een heel ander moment,
 * waarna de pariteitsmeting iets vergelijkt wat niet vergelijkbaar is.
 */
internal class ExportedFrameGrabber(private val file: File) : AutoCloseable {

    private val retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }

    /**
     * Het geschaalde resultaat, want SSIM vergelijkt alleen frames van gelijk
     * formaat.
     *
     * Niet via `getScaledFrameAtTime`: dat bestaat pas vanaf API 27 en minSdk is 26.
     * In de praktijk is het frame al de goede maat — de export heeft de resolutie
     * uit het renderplan — dus het schalen is een vangnet en geen vaste stap.
     */
    fun frameAt(timeUs: Long, width: Int, height: Int): Bitmap? {
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null

        if (frame.width == width && frame.height == height) return frame

        return Bitmap.createScaledBitmap(frame, width, height, true).also {
            if (it !== frame) frame.recycle()
        }
    }

    override fun close() {
        runCatching { retriever.release() }
    }
}

/**
 * Frames zoals de **preview** ze laat zien.
 *
 * Dit is de helft van bewijs 1 die niet uit een bestand te halen is: de
 * [CompositionPlayer] rendert naar een Surface en nergens anders heen. Door die
 * Surface van een [ImageReader] te laten komen, is af te lezen wat er werkelijk
 * op het scherm zou staan — dezelfde compositie, de andere weg erdoorheen.
 *
 * De speler moet op een thread met een Looper leven; in de praktijk de hoofdthread.
 * Alle publieke functies hier gaan daarvan uit.
 */
internal class PreviewFrameGrabber(
    context: Context,
    private val composition: Composition,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {

    private val readerThread = HandlerThread("preview-reader").apply { start() }

    private val images = Channel<Unit>(Channel.CONFLATED)

    private val imageReader: ImageReader =
        ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, MAX_IMAGES)
            .apply {
                setOnImageAvailableListener(
                    { images.trySend(Unit) },
                    Handler(readerThread.looper),
                )
            }

    private val player: CompositionPlayer = CompositionPlayer.Builder(context).build().apply {
        setVideoSurface(imageReader.surface, Size(width, height))
        // Niet afspelen: de spike wil losse frames op vaste tijdstippen, geen film.
        playWhenReady = false
        setComposition(composition)
        prepare()
    }

    /** Wacht tot de speler klaar is met laden, of geeft op na [READY_TIMEOUT_MS]. */
    suspend fun awaitReady(): Boolean {
        if (player.playbackState == Player.STATE_READY) return true

        val ready = CompletableDeferred<Boolean>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready.complete(true)
                if (state == Player.STATE_IDLE) ready.complete(false)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                ready.complete(false)
            }
        }
        player.addListener(listener)

        return try {
            withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } ?: false
        } finally {
            player.removeListener(listener)
        }
    }

    /**
     * Springt naar [timeUs] en leest het frame dat daarna verschijnt.
     *
     * Eerst de wachtrij leegmaken: zonder dat zou het eerstvolgende beschikbare
     * beeld nog van de vorige positie kunnen zijn, en dan meet de pariteitstest een
     * verschil dat er niet is.
     */
    suspend fun frameAt(timeUs: Long): Bitmap? {
        drainPending()

        player.seekTo(timeUs / 1_000L)

        val arrived = withTimeoutOrNull(FRAME_TIMEOUT_MS) { images.receive() }
        if (arrived == null) return null

        val image = imageReader.acquireLatestImage() ?: return null
        return try {
            image.toBitmap(width, height)
        } finally {
            image.close()
        }
    }

    private fun drainPending() {
        while (true) {
            val image = imageReader.acquireLatestImage() ?: break
            image.close()
        }
        while (images.tryReceive().isSuccess) {
            // Alleen de teller leegtrekken; de beelden zijn hierboven al gesloten.
        }
    }

    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { player.release() }
        }
        runCatching { imageReader.close() }
        readerThread.quitSafely()
        images.close()
    }

    private companion object {
        /**
         * Twee is genoeg: er wordt frame voor frame gelezen, en een diepe wachtrij
         * maakt het alleen maar waarschijnlijker dat er een oud beeld uit komt.
         */
        const val MAX_IMAGES = 2
        const val READY_TIMEOUT_MS = 15_000L
        const val FRAME_TIMEOUT_MS = 8_000L
    }
}

/**
 * Kopieert een [android.media.Image] naar een bitmap, met de rij-opvulling eruit.
 *
 * De rijen van een Image zijn vrijwel altijd breder dan het beeld — de GPU wil
 * uitgelijnde rijen. Klakkeloos kopiëren levert een scheefgetrokken beeld op.
 */
private fun android.media.Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val buffer = plane.buffer

    val padding = rowStride - pixelStride * width
    val bitmap = Bitmap.createBitmap(
        width + padding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888,
    )
    bitmap.copyPixelsFromBuffer(buffer)

    if (bitmap.width == width) return bitmap

    return Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
        if (it !== bitmap) bitmap.recycle()
    }
}
