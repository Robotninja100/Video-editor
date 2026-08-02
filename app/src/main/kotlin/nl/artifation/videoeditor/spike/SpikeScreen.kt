package nl.artifation.videoeditor.spike

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * De fase 0-poort als één scherm met één knop.
 *
 * De opzet is bewust saai: druk op de knop, wacht, lees af. Wat op het scherm
 * komt is de uitslag in gewone taal, met daaronder de meetwaarden en een paar
 * frames — zodat er ook met het oog te controleren valt of het klopt, en niet
 * alleen op mijn woord.
 */
@Composable
internal fun SpikeScreen(
    viewModel: SpikeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Fase 0 — de poort",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Text(
                text = "Deze meting controleert twee dingen op dit toestel: of preview en " +
                    "export hetzelfde beeld geven, en of de blur buiten het mask blijft — " +
                    "ook op een clip die getrimd is. Zakt de tweede, dan moet de opslag van " +
                    "maskers terug naar de tekentafel.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            Button(
                onClick = viewModel::start,
                enabled = state !is SpikeUiState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is SpikeUiState.Done) "Nog een keer meten" else "Meting starten")
            }
        }

        when (val current = state) {
            is SpikeUiState.Idle -> item {
                Text(
                    text = "Reken op een paar minuten. Het testmateriaal wordt de eerste keer " +
                        "op het toestel gemaakt; daarna gaat het sneller.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is SpikeUiState.Running -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(current.status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            is SpikeUiState.Done -> {
                val report = current.report

                item { VerdictCard(report) }

                report.failure?.let { failure ->
                    item {
                        Section("De meting strandde") {
                            // De echte foutmelding, niet "er ging iets mis": zonder de
                            // tekst valt er niets te repareren.
                            Text(failure, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                report.parity?.let { parity ->
                    item {
                        Section("Bewijs 1 — pariteit", parity.passed) {
                            Text(parity.explanation, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            parity.samples.forEach { sample ->
                                Text(
                                    text = "  ${formatSeconds(sample.timeUs)}s   SSIM " +
                                        (sample.ssim?.let { formatScore(it) } ?: "geen frame"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                listOfNotNull(report.blur, report.blurTrimmed).forEach { blur ->
                    item {
                        Section("Bewijs 2 — masked blur (${blur.label})", blur.passed) {
                            Text(blur.explanation, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "  tijd     binnen/buiten   bron/cliptijd",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            blur.samples.forEach { sample ->
                                Text(
                                    text = "  ${formatSeconds(sample.clipTimeUs)}s     " +
                                        "${sample.sharpnessRatio?.let { formatScore(it) } ?: "—"}×          " +
                                        "${sample.syncRatio?.let { formatScore(it) + "×" } ?: "n.v.t."}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                if (report.samples.isNotEmpty()) {
                    item {
                        Text(
                            text = "Beelden om zelf te beoordelen",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                items(report.samples) { sample ->
                    Section(sample.label) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FrameThumbnail(sample.left)
                            sample.right?.let { FrameThumbnail(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(report: SpikeReport) {
    val colour = when {
        report.failure != null -> Color(0xFF7A5B00)
        report.passed -> Color(0xFF1B5E20)
        else -> Color(0xFF7F1D1D)
    }

    Surface(
        color = colour,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = report.verdict,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Section(
    title: String,
    passed: Boolean? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            passed?.let {
                Text(if (it) "✓  " else "✗  ", style = MaterialTheme.typography.titleMedium)
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun FrameThumbnail(sample: Pair<android.graphics.Bitmap, String>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = sample.first.asImageBitmap(),
            contentDescription = sample.second,
            modifier = Modifier
                .width(140.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(sample.second, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatSeconds(timeUs: Long): String =
    String.format(java.util.Locale.ROOT, "%4.1f", timeUs / 1_000_000.0)

private fun formatScore(value: Double): String =
    String.format(java.util.Locale.ROOT, "%.2f", value)
