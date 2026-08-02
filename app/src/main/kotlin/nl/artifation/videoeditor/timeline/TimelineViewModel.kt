package nl.artifation.videoeditor.timeline

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.EditHistory
import nl.artifation.videoeditor.model.Gap
import nl.artifation.videoeditor.model.Sequence
import nl.artifation.videoeditor.model.Us
import nl.artifation.videoeditor.model.moveClipTo
import nl.artifation.videoeditor.model.normalized
import nl.artifation.videoeditor.model.rippleDelete
import nl.artifation.videoeditor.model.splitAt
import nl.artifation.videoeditor.model.trimClip

internal data class TimelineUiState(
    val sequence: Sequence,
    val playheadUs: Us,
    val selectedIndex: Int?,
    val canUndo: Boolean,
    val canRedo: Boolean,
) {
    val durationUs: Us get() = sequence.durationUs
    val itemStartsUs: List<Us> get() = sequence.itemStartsUs()
}

/**
 * De toestand van het tijdlijnscherm.
 *
 * Alle bewerkingen zitten al in `EditOps.kt` en zijn daar getest; dit is niet meer
 * dan de doorgeefluik ernaartoe, plus de geschiedenis en de playhead. Dat is met
 * opzet zo dun: zodra hier logica insluipt die niet in `:core-model` getest is,
 * is die logica alleen nog op een toestel te controleren.
 */
internal class TimelineViewModel(initial: Sequence = demoSequence()) : ViewModel() {

    private val history = EditHistory(initial)

    private val _state = MutableStateFlow(
        TimelineUiState(
            sequence = initial,
            playheadUs = 0L,
            selectedIndex = null,
            canUndo = false,
            canRedo = false,
        ),
    )
    val state: StateFlow<TimelineUiState> = _state.asStateFlow()

    fun scrubTo(timelineUs: Us) {
        _state.value = _state.value.copy(
            playheadUs = timelineUs.coerceIn(0L, _state.value.durationUs),
        )
    }

    fun select(index: Int?) {
        _state.value = _state.value.copy(selectedIndex = index)
    }

    fun splitAtPlayhead() = apply { it.splitAt(_state.value.playheadUs) }

    fun deleteSelected() {
        val index = _state.value.selectedIndex ?: return
        apply { it.rippleDelete(index).normalized() }
        _state.value = _state.value.copy(selectedIndex = null)
    }

    /**
     * Trimt de geselecteerde clip aan één rand.
     *
     * [deltaUs] is een verschuiving in **bron**tijd, want dat is waar in- en
     * uitpunten in staan. Positief schuift naar later.
     */
    fun trimSelected(deltaUs: Us, leadingEdge: Boolean) {
        val index = _state.value.selectedIndex ?: return
        val clip = _state.value.sequence.items.getOrNull(index) as? Clip ?: return

        val newIn = if (leadingEdge) clip.inPointUs + deltaUs else clip.inPointUs
        val newOut = if (leadingEdge) clip.outPointUs else clip.outPointUs + deltaUs

        // De grenzen hier afvangen en niet aan trimClip overlaten: die gooit, en een
        // gebruiker die te ver sleept hoort geen crash te krijgen maar een randje.
        if (newIn < 0L || newOut <= newIn) return

        apply { it.trimClip(index, newIn, newOut) }
    }

    fun moveSelectedTo(targetStartUs: Us) {
        val index = _state.value.selectedIndex ?: return
        if (_state.value.sequence.items.getOrNull(index) !is Clip) return
        apply { it.moveClipTo(index, targetStartUs.coerceAtLeast(0L)).normalized() }
    }

    fun undo() {
        publish(history.undo())
    }

    fun redo() {
        publish(history.redo())
    }

    private fun apply(transform: (Sequence) -> Sequence) {
        val next = transform(history.current)
        history.push(next)
        publish(history.current)
    }

    private fun publish(sequence: Sequence) {
        val current = _state.value
        _state.value = current.copy(
            sequence = sequence,
            // De playhead mag niet voorbij het einde blijven staan na een verwijdering.
            playheadUs = current.playheadUs.coerceIn(0L, sequence.durationUs),
            selectedIndex = current.selectedIndex?.takeIf { it in sequence.items.indices },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }
}

/**
 * Iets om op te bouwen zolang media importeren nog niet werkt.
 *
 * Verwijst naar bestanden die er niet zijn: de tijdlijn zelf, het knippen en het
 * schuiven werken hier prima mee, want dat is allemaal rekenwerk op tijden. Pas
 * preview en export hebben echt materiaal nodig.
 */
internal fun demoSequence(): Sequence = Sequence(
    id = "main",
    items = listOf(
        Clip(id = "a", sourceUri = "demo://a", inPointUs = 0L, outPointUs = 4_000_000L),
        Gap(durationUs = 1_000_000L),
        Clip(id = "b", sourceUri = "demo://b", inPointUs = 2_000_000L, outPointUs = 8_000_000L),
        Clip(id = "c", sourceUri = "demo://c", inPointUs = 0L, outPointUs = 3_000_000L),
    ),
)
