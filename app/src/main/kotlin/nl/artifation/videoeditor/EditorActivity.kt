package nl.artifation.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
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

            EditorScreen(
                state = model.state(player = null, analysis = null),
                actions = model.actions(),
            )
        }
    }
}
