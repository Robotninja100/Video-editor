package nl.artifation.videoeditor.spike

import android.content.Context
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Exporteert een compositie naar een bestand en meldt de voortgang.
 *
 * Dezelfde code als waar de export in de app straks op draait; de spike gebruikt
 * hem alleen met testmateriaal. Dat is de bedoeling: een spike die zijn eigen
 * exportpad heeft bewijst niets over het echte.
 */
internal class CompositionExporter(private val context: Context) {

    /**
     * Moet op een thread met een Looper aangeroepen worden — Transformer eist dat.
     *
     * Geeft het resultaat terug, of gooit de [ExportException] door zodat de
     * aanroeper de echte foutmelding te zien krijgt in plaats van "er ging iets mis".
     */
    suspend fun export(
        composition: Composition,
        target: File,
        onProgress: (Int) -> Unit = {},
    ): ExportResult {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val done = CompletableDeferred<ExportResult>()

        val transformer = Transformer.Builder(context)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        done.complete(result)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        done.completeExceptionally(exception)
                    }
                },
            )
            .build()

        transformer.start(composition, target.absolutePath)

        val holder = ProgressHolder()
        try {
            while (!done.isCompleted) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                if (!coroutineContext.isActive) {
                    // Een geannuleerde export moet ook de codecs loslaten, anders
                    // blijft de encoder bezet en mislukt de volgende poging.
                    transformer.cancel()
                    throw kotlinx.coroutines.CancellationException("export afgebroken")
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { transformer.cancel() } }
            throw cancellation
        }

        return done.await()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
