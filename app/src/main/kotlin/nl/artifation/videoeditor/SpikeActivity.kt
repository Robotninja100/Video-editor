package nl.artifation.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import nl.artifation.videoeditor.spike.SpikeScreen
import nl.artifation.videoeditor.spike.SpikeViewModel

/**
 * De fase 0-poort, als scherm.
 *
 * Bewust een eigen activity met een eigen ingang in de launcher, en niet een knop
 * in de editor. Dit is een meetinstrument, geen functie: het maakt zijn eigen
 * bron- en maskvideo aan, exporteert, speelt af, en vergelijkt de frames. Het
 * hoort niet in de weg te zitten bij normaal gebruik, en het hoort ook niet weg
 * te vallen zodra de editor verandert.
 *
 * Wat het uitwijst staat in `docs/PRODUCTPLAN.md` onder fase 0. Zolang deze twee
 * bewijzen niet groen zijn op een echt toestel, staat de rest van de roadmap op
 * een aanname.
 */
@UnstableApi
public class SpikeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val model: SpikeViewModel = viewModel()
            SpikeScreen(viewModel = model)
        }
    }
}
