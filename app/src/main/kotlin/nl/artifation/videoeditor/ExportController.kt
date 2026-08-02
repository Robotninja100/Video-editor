package nl.artifation.videoeditor

import android.content.Context
import android.os.Environment
import androidx.media3.common.util.UnstableApi
import nl.artifation.videoeditor.model.ExportPreset
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.toCompositionPlan
import nl.artifation.videoeditor.render.Exporter
import java.io.File

/**
 * Houdt de lopende export vast.
 *
 * Bestaat omdat [Exporter] een `Context` nodig heeft en [EditorViewModel] die
 * bewust niet mag hebben: een ViewModel die een Activity vasthoudt, houdt hem
 * ook vast als het scherm draait. De ViewModel weet alleen nog wát er van de
 * export te melden valt; het starten en stoppen zit hier.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README.
 */
@UnstableApi
internal class ExportController(private val context: Context) {

    private var lopend: Exporter? = null

    /**
     * Start een export van [project].
     *
     * De preset wordt uit het project zelf afgeleid en niet vast gekozen: dan
     * kunnen het formaat van het renderplan en dat van de preset per constructie
     * niet uit elkaar lopen, en dat is precies waar [Exporter] op controleert.
     *
     * Fouten vóór het starten — geen schrijfbare map, een leeg project — komen
     * langs dezelfde weg terug als fouten tijdens het renderen. Voor wie kijkt is
     * het immers hetzelfde: de export is niet gelukt.
     */
    fun start(project: Project, listener: Exporter.Listener) {
        cancel()

        runCatching {
            val exporter = Exporter(context, ExportPreset.forSpec(project.outputSpec))
            lopend = exporter
            exporter.start(project.toCompositionPlan(), doelbestand().absolutePath, listener)
        }.onFailure { oorzaak ->
            lopend = null
            listener.onFailed(oorzaak)
        }
    }

    fun cancel() {
        lopend?.cancel()
        lopend = null
    }

    /**
     * Een eigen map in de app-opslag, niet de galerij.
     *
     * Wegschrijven naar de galerij vraagt om MediaStore en om een keuze over wat
     * er met een mislukte export gebeurt. Dat hoort bij de afwerking; voor nu is
     * een pad dat je kunt delen genoeg om te zien of het rondje klopt.
     */
    private fun doelbestand(): File {
        val map = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        map.mkdirs()
        return File(map, "export-${System.currentTimeMillis()}.mp4")
    }
}
