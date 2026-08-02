package nl.artifation.videoeditor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.Us
import nl.artifation.videoeditor.render.Exporter
import nl.artifation.videoeditor.ui.EditorScreen

/**
 * Het enige scherm van de app.
 *
 * `enableEdgeToEdge` is hier geen afwerking maar de kern van het ontwerp: de
 * video vult het scherm tot achter de status- en navigatiebalk, en de panelen
 * liggen er als glas overheen. Zonder dit staat er een systeemvlak overheen.
 *
 * **Niet gecompileerd.** Zie README: `:app` staat nog niet in de build.
 */
@UnstableApi
public class EditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val model: EditorViewModel = viewModel()
            val context = LocalContext.current
            val export = remember(context) { ExportController(context) }

            // Vasthouden aan het model en niet aan de compositie: de listener
            // wordt door Media3 aangeroepen, mogelijk nadat dit scherm al opnieuw
            // is opgebouwd.
            val exportListener = remember(model) {
                object : Exporter.Listener {
                    override fun onProgress(percent: Int) = model.onExportProgress(percent)

                    override fun onCompleted(outputPath: String) =
                        model.onExportCompleted(outputPath)

                    override fun onFailed(cause: Throwable) =
                        model.onExportFailed(cause.message ?: "onbekende fout")
                }
            }

            // Zonder materiaal is er niets te monteren, dus de kiezer komt
            // meteen. Een lege tijdlijn tonen en wachten tot iemand een knop
            // vindt is geen begin, dat is een doodlopend scherm.
            GekozenVideo { uri, durationUs -> model.openClip(uri.toString(), durationUs) }

            EditorScreen(
                state = model.state(
                    player = rememberPreviewPlayer(model.project),
                    analysis = null,
                ),
                actions = model.actions(
                    onExport = {
                        model.onExportProgress(0)
                        export.start(model.project, exportListener)
                    },
                    onCancelExport = export::cancel,
                ),
            )
        }
    }
}

/**
 * Vraagt de gebruiker één video en levert die met zijn duur af.
 *
 * De duur komt van [MediaMetadataRetriever] en niet van de speler: het
 * projectmodel heeft een out-point nodig vóórdat er iets af te spelen valt.
 * Uitlezen kost bij een groot bestand tientallen milliseconden en gebeurt
 * daarom niet op de hoofdthread.
 */
@Composable
private fun GekozenVideo(onGekozen: (Uri, Us) -> Unit) {
    val context = LocalContext.current
    // Het typeargument moet erbij: `mutableStateOf(null)` levert een
    // `MutableState<Nothing?>` op, en daar valt de gekozen uri niet in te zetten.
    var gekozen by remember { mutableStateOf<Uri?>(null) }
    var gevraagd by remember { mutableStateOf(false) }

    val kiezer = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> gekozen = uri }

    LaunchedEffect(Unit) {
        if (!gevraagd) {
            gevraagd = true
            kiezer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
    }

    LaunchedEffect(gekozen) {
        val uri = gekozen ?: return@LaunchedEffect
        val durationUs = withContext(Dispatchers.IO) { durationUsOf(context, uri) }
        if (durationUs > 0L) onGekozen(uri, durationUs)
    }
}

/** De duur in microseconden, of 0 als het bestand niets bruikbaars meldt. */
private fun durationUsOf(context: Context, uri: Uri): Us {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val ms = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
        ms * US_PER_MS
    } catch (ignored: RuntimeException) {
        // setDataSource gooit bij een uri die de provider niet meer geeft, en bij
        // een bestand dat geen media is. Beide zijn "geen bruikbare video",
        // niet iets om de app op te laten vallen.
        0L
    } finally {
        retriever.release()
    }
}
