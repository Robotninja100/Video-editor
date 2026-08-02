package nl.artifation.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.artifation.videoeditor.spike.SpikeScreen
import nl.artifation.videoeditor.spike.SpikeViewModel
import nl.artifation.videoeditor.timeline.TimelineScreen
import nl.artifation.videoeditor.timeline.TimelineViewModel

/**
 * Twee schermen, meer niet.
 *
 * Het Spike-scherm is voorlopig het belangrijkste van de twee: zolang de fase
 * 0-poort niet gehaald is, staat alles wat op de tijdlijn gebouwd wordt op losse
 * grond. Zie `docs/CHECKLIST.md`.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoEditorApp() }
    }
}

@Composable
private fun VideoEditorApp() {
    // Donker, en alleen donker: video beoordeel je niet op een witte achtergrond.
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var tab by remember { mutableIntStateOf(0) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.systemBars.asPaddingValues()),
            ) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Fase 0") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Tijdlijn") })
                }

                when (tab) {
                    0 -> SpikeScreen(viewModel = viewModel<SpikeViewModel>())
                    else -> TimelineScreen(viewModel = viewModel<TimelineViewModel>())
                }
            }
        }
    }
}
