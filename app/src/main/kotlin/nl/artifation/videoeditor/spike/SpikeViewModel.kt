package nl.artifation.videoeditor.spike

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface SpikeUiState {
    data object Idle : SpikeUiState
    data class Running(val status: String) : SpikeUiState
    data class Done(val report: SpikeReport) : SpikeUiState
}

/**
 * Houdt de meting draaiende over een schermrotatie heen.
 *
 * De meting duurt minuten en encodeert en exporteert video; halverwege opnieuw
 * beginnen omdat het toestel gedraaid werd is onnodig en verwarrend.
 */
internal class SpikeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<SpikeUiState>(SpikeUiState.Idle)
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()

    private var running: Job? = null

    fun start() {
        if (running?.isActive == true) return

        running = viewModelScope.launch {
            _state.value = SpikeUiState.Running("Starten…")

            // De hoofdthread, met opzet: zowel Transformer als CompositionPlayer
            // eisen een thread met een Looper, en het zware werk verhuist binnen de
            // meting zelf naar een achtergronddispatcher.
            val report = SpikeRunner(getApplication()).run { status ->
                _state.value = SpikeUiState.Running(status)
            }

            _state.value = SpikeUiState.Done(report)
        }
    }
}
