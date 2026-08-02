package nl.artifation.videoeditor.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.Gap
import nl.artifation.videoeditor.model.Us

/**
 * De tijdlijn: scrubben, selecteren, knippen, trimmen en verslepen.
 *
 * Het scherm roept alleen bewerkingen aan die in `:core-model` staan en daar
 * getest zijn. Wat hier gebeurt is het omrekenen van vingers naar microseconden
 * en terug — verder niets.
 */
@Composable
internal fun TimelineScreen(
    viewModel: TimelineViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    // Vaste schaal: een seconde is altijd even breed. Zoomen komt later; zonder
    // vaste schaal is er geen ijkpunt om een sleepbeweging op om te rekenen.
    val pixelsPerSecond = with(density) { SECOND_WIDTH.toPx() }

    fun offsetToUs(offsetX: Float): Us =
        (offsetX / pixelsPerSecond * 1_000_000L).toLong().coerceAtLeast(0L)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tijdlijn", style = MaterialTheme.typography.headlineSmall)

        Text(
            text = "playhead ${formatTime(state.playheadUs)}   ·   " +
                "duur ${formatTime(state.durationUs)}   ·   " +
                "${state.sequence.items.size} items",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .width(usToDp(state.durationUs, pixelsPerSecond, density))
                    .height(TRACK_HEIGHT)
                    .pointerInput(state.durationUs) {
                        detectTapGestures(
                            onTap = { offset ->
                                viewModel.scrubTo(offsetToUs(offset.x))
                                viewModel.select(indexAt(state, offsetToUs(offset.x)))
                            },
                        )
                    }
                    .pointerInput(state.durationUs, state.selectedIndex) {
                        var accumulated = 0f
                        detectDragGestures(
                            onDragStart = { accumulated = 0f },
                            onDragEnd = {
                                // Pas bij het loslaten verplaatsen: tijdens het slepen elke
                                // pixel een moveClipTo doen zou de geschiedenis volgooien
                                // met tussenstappen die niemand terug wil.
                                val index = state.selectedIndex ?: return@detectDragGestures
                                val start = state.itemStartsUs.getOrNull(index)
                                    ?: return@detectDragGestures
                                viewModel.moveSelectedTo(start + offsetToUs(accumulated))
                            },
                            onDrag = { change, dragAmount: Offset ->
                                change.consume()
                                accumulated += dragAmount.x
                            },
                        )
                    },
            ) {
                var startUs = 0L
                state.sequence.items.forEachIndexed { index, item ->
                    TimelineItemBlock(
                        label = when (item) {
                            is Clip -> item.id
                            is Gap -> "gat"
                        },
                        isGap = item is Gap,
                        isSelected = index == state.selectedIndex,
                        offset = usToDp(startUs, pixelsPerSecond, density),
                        width = usToDp(item.durationUs, pixelsPerSecond, density),
                    )
                    startUs += item.durationUs
                }

                Box(
                    modifier = Modifier
                        .offsetDp(usToDp(state.playheadUs, pixelsPerSecond, density))
                        .width(2.dp)
                        .height(TRACK_HEIGHT)
                        .background(Color.Red),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::splitAtPlayhead) { Text("Splitsen") }
            OutlinedButton(
                onClick = viewModel::deleteSelected,
                enabled = state.selectedIndex != null,
            ) { Text("Verwijderen") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.trimSelected(-TRIM_STEP_US, leadingEdge = true) },
                enabled = state.selectedIndex != null,
            ) { Text("◀ in") }
            OutlinedButton(
                onClick = { viewModel.trimSelected(TRIM_STEP_US, leadingEdge = true) },
                enabled = state.selectedIndex != null,
            ) { Text("in ▶") }
            OutlinedButton(
                onClick = { viewModel.trimSelected(-TRIM_STEP_US, leadingEdge = false) },
                enabled = state.selectedIndex != null,
            ) { Text("◀ uit") }
            OutlinedButton(
                onClick = { viewModel.trimSelected(TRIM_STEP_US, leadingEdge = false) },
                enabled = state.selectedIndex != null,
            ) { Text("uit ▶") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("Ongedaan maken") }
            OutlinedButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("Opnieuw") }
        }

        Text(
            text = "Tik om te scrubben en te selecteren, sleep om de selectie te verplaatsen.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TimelineItemBlock(
    label: String,
    isGap: Boolean,
    isSelected: Boolean,
    offset: Dp,
    width: Dp,
) {
    Box(
        modifier = Modifier
            .offsetDp(offset)
            .width(width)
            .height(TRACK_HEIGHT)
            .padding(horizontal = 1.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isGap -> Color(0xFF2A2A2A)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isGap) Color.Gray else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun Modifier.offsetDp(x: Dp): Modifier = this.then(
    Modifier.padding(start = x),
)

private fun usToDp(
    us: Us,
    pixelsPerSecond: Float,
    density: androidx.compose.ui.unit.Density,
): Dp = with(density) { (us / 1_000_000f * pixelsPerSecond).toDp() }

private fun indexAt(state: TimelineUiState, timelineUs: Us): Int? =
    state.sequence.locate(timelineUs)?.index

private fun formatTime(us: Us): String {
    val totalSeconds = us / 1_000_000
    val millis = (us % 1_000_000) / 1_000
    return String.format(java.util.Locale.ROOT, "%d:%02d.%03d", totalSeconds / 60, totalSeconds % 60, millis)
}

private val TRACK_HEIGHT = 88.dp
private val SECOND_WIDTH = 60.dp
private const val TRIM_STEP_US = 250_000L
