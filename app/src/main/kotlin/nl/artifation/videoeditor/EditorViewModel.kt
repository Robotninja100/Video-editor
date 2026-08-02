package nl.artifation.videoeditor

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.Sequence
import nl.artifation.videoeditor.model.TimelineGeometry
import nl.artifation.videoeditor.model.UndoStack
import nl.artifation.videoeditor.model.Us
import nl.artifation.videoeditor.analysis.AudioAnalysis
import nl.artifation.videoeditor.model.moveClipTo
import nl.artifation.videoeditor.model.rippleDelete
import nl.artifation.videoeditor.model.sequenceFromIntervals
import nl.artifation.videoeditor.model.splitAt
import nl.artifation.videoeditor.ui.AnalysisProgress
import nl.artifation.videoeditor.ui.EditorActions
import nl.artifation.videoeditor.ui.EditorState
import nl.artifation.videoeditor.ui.ExportStatus
import nl.artifation.videoeditor.ui.SilenceProposal

/**
 * De brug tussen de pure modules en het scherm.
 *
 * Alle beslissingen staan in `:core-model` — bewerkingen, ongedaan maken,
 * tijdlijn-geometrie. Deze klasse houdt alleen bij wát er nu geldt en geeft
 * bewerkingen door. Dat is bewust dun: elke regel logica die hier zou staan, is
 * een regel die niet getest kan worden zonder toestel.
 *
 * **Niet gecompileerd.** Zie README: `:app` staat nog niet in de build.
 */
public class EditorViewModel(
    initial: Project = leegProject(),
) : ViewModel() {

    private val history = UndoStack(initial)

    public var playheadUs: Us by mutableStateOf(0L)
        private set

    public var selectedClipId: String? by mutableStateOf<String?>(null)
        private set

    public var pxPerSecond: Float by mutableStateOf(STANDAARD_ZOOM)
        private set

    public var scrollPx: Float by mutableStateOf(0f)
        private set

    public var exportStatus: ExportStatus? by mutableStateOf<ExportStatus?>(null)
        private set

    public var analysisProgress: AnalysisProgress? by mutableStateOf<AnalysisProgress?>(null)
        private set

    public var silenceProposal: SilenceProposal? by mutableStateOf<SilenceProposal?>(null)
        private set

    /** De bron waar de analyse over ging; nodig om de tijdlijn opnieuw op te bouwen. */
    public val sourceUri: String?
        get() = project.mainSequence.items.filterIsInstance<Clip>().firstOrNull()?.sourceUri

    /**
     * De volle duur van het bronbestand, vastgelegd bij het openen.
     *
     * Dient als revisienummer voor de sidecar. Bewust niet de projectduur: die
     * verandert zodra je iets wegknipt, en dan zou elke montage de analyse
     * ongeldig maken terwijl het bronmateriaal hetzelfde is gebleven.
     */
    public var sourceRevision: Us = 0L
        private set

    private var revisie: Int by mutableStateOf(0)

    /** Het project zoals het nu is; `revisie` maakt het leesbaar voor Compose. */
    public val project: Project
        get() {
            revisie // gelezen zodat Compose op wijzigingen let
            return history.current
        }

    public val canUndo: Boolean get() = run { revisie; history.canUndo }
    public val canRedo: Boolean get() = run { revisie; history.canRedo }

    @UnstableApi
    public fun state(player: Player?): EditorState =
        EditorState(
            project = project,
            playheadUs = playheadUs,
            selectedClipId = selectedClipId,
            pxPerSecond = pxPerSecond,
            scrollPx = scrollPx,
            player = player,
            analysis = analysisProgress,
            canUndo = canUndo,
            canRedo = canRedo,
            canDelete = selectedClipId != null,
            export = exportStatus,
            silenceProposal = silenceProposal,
        )

    /**
     * @param onExport start de export; die heeft een `Context` nodig en hoort
     *   daarom niet in een ViewModel thuis. De Activity levert hem aan.
     */
    public fun actions(
        onExport: () -> Unit = {},
        onCancelExport: () -> Unit = {},
        onAnalyzeAudio: () -> Unit = {},
    ): EditorActions = EditorActions(
        onScrub = { playheadUs = it.coerceAtLeast(0L) },
        onSelect = { selectedClipId = it },
        onMove = ::verplaatsClip,
        onZoom = ::zoom,
        onCancelAnalysis = { /* de wachtrij annuleert; zie AnalysisService */ },
        onUndo = ::undo,
        onRedo = ::redo,
        onSplit = ::splitsOpPlayhead,
        onDelete = ::verwijderSelectie,
        onExport = onExport,
        onCancelExport = {
            onCancelExport()
            exportStatus = null
        },
        onDismissExport = { exportStatus = null },
        onAnalyzeAudio = onAnalyzeAudio,
        onApplySilenceCut = ::pasStiltesToe,
        onDismissProposal = { silenceProposal = null },
    )

    public fun onAnalysisProgress(fraction: Float) {
        analysisProgress = AnalysisProgress(
            label = "Stiltes zoeken",
            fraction = fraction,
            // Lokale analyse; er gaat geen materiaal naar een dienst en er is
            // dus niets te betalen. Zie het bouwplan, §Analyse lokaal of in de cloud.
            estimatedUsd = null,
        )
    }

    /**
     * Verwerkt het analyseresultaat tot een voorstel.
     *
     * Zonder stiltes komt er geen paneel: een melding dat er niets te knippen
     * valt, is een paneel dat je moet wegklikken om te horen dat er niets gebeurt.
     */
    public fun onAnalysisCompleted(analysis: AudioAnalysis?) {
        analysisProgress = null

        val stiltes = analysis?.silences.orEmpty()
        if (analysis == null || stiltes.isEmpty()) {
            silenceProposal = null
            return
        }

        val keeps = analysis.keepIntervals()
        val weggehaald = stiltes.sumOf { it.endUs - it.startUs }

        silenceProposal = SilenceProposal(
            silenceCount = stiltes.size,
            removedUs = weggehaald,
            resultingDurationUs = (analysis.durationUs - weggehaald).coerceAtLeast(0L),
            keepIntervals = keeps,
        )
    }

    /**
     * Bouwt de hoofdtrack opnieuw op uit de stukken die blijven.
     *
     * Gaat door de gewone bewerkingsstapel, dus ↶ draait het in één druk terug.
     * Dat is de reden dat dit een bewerking is en geen aparte modus: een
     * automatische montage die je niet ongedaan kunt maken, is een gok.
     */
    private fun pasStiltesToe() {
        val proposal = silenceProposal ?: return
        val bron = sourceUri ?: return

        history.edit { huidig ->
            huidig.copy(
                sequences = huidig.sequences.mapIndexed { index, sequence ->
                    if (index == 0) {
                        sequenceFromIntervals(sequence.id, bron, proposal.keepIntervals)
                    } else {
                        sequence
                    }
                },
            )
        }

        silenceProposal = null
        selectedClipId = null
        playheadUs = 0L
        revisie++
    }

    public fun onExportProgress(percent: Int) {
        exportStatus = ExportStatus.Running(percent)
    }

    public fun onExportCompleted(outputPath: String) {
        exportStatus = ExportStatus.Done(outputPath)
    }

    public fun onExportFailed(message: String) {
        exportStatus = ExportStatus.Failed(message)
    }

    /**
     * Knipt alle sporen door op de playhead.
     *
     * Alle sporen en niet alleen het geselecteerde: beeld en geluid horen na een
     * knip op dezelfde plek te liggen, anders schuift het geluid weg zodra je een
     * stuk beeld verplaatst. Op een itemgrens doet `splitAt` niets, dus een knip
     * op een plek waar al geknipt is verandert niets.
     */
    private fun splitsOpPlayhead() {
        history.edit { huidig ->
            huidig.copy(sequences = huidig.sequences.map { it.splitAt(playheadUs) })
        }
        revisie++
    }

    /**
     * Haalt de geselecteerde clip weg; alles erachter schuift op.
     *
     * Ripple en niet liften: een gat achterlaten waar je net iets weghaalde is
     * bijna nooit wat je bedoelt, en het gat is met slepen zo terug te maken.
     */
    private fun verwijderSelectie() {
        val id = selectedClipId ?: return
        history.edit { huidig ->
            huidig.copy(
                sequences = huidig.sequences.map { sequence ->
                    val index = sequence.items.indexOfFirst { it is Clip && it.id == id }
                    if (index < 0) sequence else sequence.rippleDelete(index)
                },
            )
        }
        selectedClipId = null
        revisie++
    }

    /**
     * Zet een gekozen video als enige clip op de videotrack.
     *
     * Het minimum om iets te kunnen zien: één bron, hele lengte, geen effecten.
     * Importeren, meerdere clips en de mediabibliotheek horen bij fase 1; dit is
     * wat fase 0 nodig heeft om preview en export tegen elkaar te kunnen leggen.
     */
    public fun openClip(sourceUri: String, durationUs: Us) {
        history.edit { huidig ->
            val clip = Clip(
                id = "clip-1",
                sourceUri = sourceUri,
                inPointUs = 0L,
                outPointUs = durationUs.coerceAtLeast(1L),
            )
            huidig.copy(
                sequences = huidig.sequences.mapIndexed { index, sequence ->
                    if (index == 0) sequence.copy(items = listOf(clip)) else sequence
                },
            )
        }
        playheadUs = 0L
        selectedClipId = "clip-1"
        sourceRevision = durationUs
        silenceProposal = null
        revisie++
    }

    public fun undo() {
        history.undo()
        revisie++
    }

    public fun redo() {
        history.redo()
        revisie++
    }

    private fun verplaatsClip(sequenceIndex: Int, itemIndex: Int, targetStartUs: Us) {
        history.edit { huidig ->
            val sequences = huidig.sequences.toMutableList()
            sequences[sequenceIndex] = sequences[sequenceIndex].moveClipTo(itemIndex, targetStartUs)
            huidig.copy(sequences = sequences)
        }
        revisie++
    }

    private fun zoom(factor: Float) {
        // De geometrie kent de grenzen; hier wordt niets bedacht.
        val geometry = TimelineGeometry(pxPerSecond = pxPerSecond, scrollPx = scrollPx)
            .zoomedBy(factor, anchorX = 0f)
        pxPerSecond = geometry.pxPerSecond
        scrollPx = geometry.scrollPx
    }

    public companion object {
        /** Ongeveer tien seconden op een telefoonbreedte. */
        public const val STANDAARD_ZOOM: Float = 40f

        public fun leegProject(): Project = Project(
            id = "nieuw",
            sequences = listOf(Sequence(id = "video"), Sequence(id = "audio")),
        )
    }
}
