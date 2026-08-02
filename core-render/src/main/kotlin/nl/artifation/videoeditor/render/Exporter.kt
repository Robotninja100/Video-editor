package nl.artifation.videoeditor.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import nl.artifation.videoeditor.model.CompositionPlan
import nl.artifation.videoeditor.model.ExportPreset

/**
 * De tijdlijn naar een bestand schrijven.
 *
 * Het sluitstuk van fase 1: tot nu toe kon het project wel afgespeeld worden maar
 * niet weggeschreven. Preview en export delen de hele effectketen — beide krijgen
 * dezelfde `Composition` uit [CompositionMapper] — en juist dat is wat fase 0 moet
 * bewijzen: dat wat je ziet ook is wat eruit komt.
 *
 * Voortgang wordt gepolld en niet gepusht, omdat Media3 het zo aanbiedt.
 * `Transformer` heeft geen voortgangscallback; hij houdt een teller bij die je
 * mag uitlezen.
 *
 * **Op de hoofdthread aanroepen.** `Transformer` wil gemaakt, gestart en gestopt
 * worden op één thread met een `Looper`, en dat is in een app de hoofdthread.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README:
 * de apk-job in CI is de eerste plek waar deze code langs een compiler gaat.
 */
@UnstableApi
public class Exporter(
    private val context: Context,
    private val preset: ExportPreset,
) {

    public interface Listener {
        /** [percent] loopt van 0 tot 100. */
        public fun onProgress(percent: Int)

        public fun onCompleted(outputPath: String)

        /** Ook bij annuleren niet aangeroepen: dat is geen fout. */
        public fun onFailed(cause: Throwable)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private var transformer: Transformer? = null

    public val isRunning: Boolean get() = transformer != null

    /**
     * @param plan het renderplan; moet hetzelfde formaat hebben als [preset]
     * @param outputPath absoluut pad naar het te schrijven bestand
     */
    public fun start(plan: CompositionPlan, outputPath: String, listener: Listener) {
        check(transformer == null) { "er loopt al een export" }
        // Twee formaten die uit elkaar lopen leveren een bestand op dat er anders
        // uitziet dan de preview, en dat merk je pas als het klaar is.
        require(plan.outputSpec == preset.outputSpec) {
            "renderplan (${plan.outputSpec}) en preset (${preset.outputSpec}) " +
                "moeten hetzelfde formaat hebben"
        }

        val composition = CompositionMapper().map(plan)
        val actief = buildTransformer(outputPath, listener)
        transformer = actief

        actief.start(composition, outputPath)
        pollProgress(listener)
    }

    /**
     * Stopt de lopende export. Doet niets als er niets loopt, zodat een tweede
     * druk op de knop geen uitzondering oplevert.
     */
    public fun cancel() {
        transformer?.cancel()
        stop()
    }

    private fun buildTransformer(outputPath: String, listener: Listener): Transformer =
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(preset.videoBitrate)
                            .build(),
                    )
                    .build(),
            )
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        stop()
                        listener.onProgress(COMPLETE_PERCENT)
                        listener.onCompleted(outputPath)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        stop()
                        listener.onFailed(exportException)
                    }
                },
            )
            .build()

    /**
     * Vraagt periodiek naar de voortgang tot de export klaar is.
     *
     * `PROGRESS_STATE_NOT_STARTED` betekent hier "niet meer bezig": de listener
     * heeft de afronding dan al gemeld. Blijven pollen zou een handler-lus in
     * leven houden voor een export die niet meer bestaat.
     */
    private fun pollProgress(listener: Listener) {
        val actief = transformer ?: return

        if (actief.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            listener.onProgress(progressHolder.progress)
        }
        handler.postDelayed({ pollProgress(listener) }, POLL_INTERVAL_MS)
    }

    private fun stop() {
        transformer = null
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val COMPLETE_PERCENT = 100
    }
}
