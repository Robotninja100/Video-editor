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
import nl.artifation.videoeditor.model.moveClipTo
import nl.artifation.videoeditor.ui.EditorActions
import nl.artifation.videoeditor.ui.EditorState

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
    public fun state(
        player: Player?,
        analysis: nl.artifation.videoeditor.ui.AnalysisProgress?,
    ): EditorState =
        EditorState(
            project = project,
            playheadUs = playheadUs,
            selectedClipId = selectedClipId,
            pxPerSecond = pxPerSecond,
            scrollPx = scrollPx,
            player = player,
            analysis = analysis,
        )

    public fun actions(): EditorActions = EditorActions(
        onScrub = { playheadUs = it.coerceAtLeast(0L) },
        onSelect = { selectedClipId = it },
        onMove = ::verplaatsClip,
        onZoom = ::zoom,
        onCancelAnalysis = { /* de wachtrij annuleert; zie AnalysisService */ },
    )

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
