package nl.artifation.videoeditor.analysis

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.artifation.videoeditor.library.AnalysisKind
import nl.artifation.videoeditor.library.FileSidecarStorage
import nl.artifation.videoeditor.library.SidecarPaths
import nl.artifation.videoeditor.library.SidecarRecord
import nl.artifation.videoeditor.library.SidecarStorage
import java.io.File

/**
 * Voert de audio-analyse uit en legt het resultaat vast.
 *
 * Dit is de schakel die er niet was: `:core-analysis` kon stiltes en loudness
 * meten, `:core-library` kon sidecars beheren, maar niemand riep ze aan. Alles
 * wat hier gebeurt is aan elkaar knopen — er wordt geen enkele beslissing
 * genomen die niet in een geteste module staat.
 *
 * Een bestaande, nog geldige sidecar wordt hergebruikt. Analyse van een lange
 * opname duurt minuten, en die twee keer draaien omdat iemand het scherm heeft
 * gedraaid is precies het soort verspilling dat de sidecar moet voorkomen.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README.
 */
internal class AudioAnalysisController(
    context: Context,
    private val storage: SidecarStorage = FileSidecarStorage(File(context.filesDir, SIDECAR_DIR)),
) {
    private val appContext = context.applicationContext

    /**
     * @param assetId stabiele naam voor deze bron; bepaalt waar de sidecar staat
     * @param sourceRevision verandert zodra het bronbestand verandert, waardoor een
     *   oude analyse vanzelf ongeldig wordt
     * @param onProgress fractie 0..1, aangeroepen op een achtergrondthread
     */
    suspend fun analyze(
        assetId: String,
        uri: Uri,
        sourceRevision: Long,
        onProgress: (Float) -> Unit = {},
    ): AudioAnalysis? = withContext(Dispatchers.IO) {
        val path = SidecarPaths.of(assetId, AnalysisKind.SILENCES)

        bestaande(path, sourceRevision)?.let { return@withContext it }

        // Decoderen is verreweg het traagste deel, dus dat stuurt de voortgang.
        val pcm = AudioDecoder.decode(appContext, uri, onProgress) ?: return@withContext null
        if (pcm.mono.isEmpty()) return@withContext null

        val analysis = AudioAnalysis(
            silences = SilenceDetector.detect(pcm.mono, pcm.sampleRate),
            loudness = Loudness.measure(listOf(pcm.mono), pcm.sampleRate),
            sampleRate = pcm.sampleRate,
            durationUs = pcm.durationUs,
        )

        storage.write(
            path,
            SidecarRecord(
                assetId = assetId,
                kind = AnalysisKind.SILENCES,
                analysisVersion = AnalysisKind.SILENCES.currentVersion,
                sourceRevision = sourceRevision,
                payload = AudioAnalysisJson.encode(analysis),
                writtenAtEpochMs = System.currentTimeMillis(),
            ),
        )

        analysis
    }

    /**
     * Een eerdere analyse, maar alleen als hij nog ergens op slaat.
     *
     * Zowel de analyseversie als de bronrevisie moeten kloppen: de eerste vangt
     * "wij rekenen nu anders", de tweede "het bestand is veranderd". Bij twijfel
     * wordt er opnieuw gemeten, want een verkeerde stiltelijst levert een montage
     * op die er niet uitziet als wat je hebt opgenomen.
     */
    private fun bestaande(path: String, sourceRevision: Long): AudioAnalysis? {
        val record = storage.read(path) ?: return null
        if (record.analysisVersion != AnalysisKind.SILENCES.currentVersion) return null
        if (record.sourceRevision != sourceRevision) return null
        return AudioAnalysisJson.decode(record.payload)
    }

    private companion object {
        const val SIDECAR_DIR = "sidecars"
    }
}
