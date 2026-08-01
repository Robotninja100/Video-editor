package nl.artifation.videoeditor.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import nl.artifation.videoeditor.design.Tokens
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.Gap
import nl.artifation.videoeditor.model.Sequence
import nl.artifation.videoeditor.model.Us

/**
 * De tijdlijn.
 *
 * Getekend op een `Canvas` en niet opgebouwd uit composables: bij honderden clips
 * en een liniaal met tientallen streepjes is recompositie per element te duur, en
 * het scrubben moet vloeiend blijven terwijl de preview meeloopt.
 *
 * Alle rekenwerk — welke clip ligt onder welke x, waar snapt een sleep naartoe —
 * staat in [TimelineGeometry] in `:core-model`-stijl pure code, zodat het
 * getest kan worden zonder toestel.
 *
 * **Niet gecompileerd.** Geen Android SDK beschikbaar; module staat nog niet in
 * `settings.gradle.kts`.
 */
@Composable
public fun Timeline(
    sequences: List<Sequence>,
    playheadUs: Us,
    selectedClipId: String?,
    geometry: TimelineGeometry,
    onScrub: (Us) -> Unit,
    onSelect: (String?) -> Unit,
    onMove: (sequenceIndex: Int, itemIndex: Int, targetStartUs: Us) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging: DragState? = null

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(geometry) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) }
            }
            .pointerInput(geometry, sequences) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = geometry.hitTest(sequences, offset.x, offset.y)
                        onSelect(hit?.clipId)
                        onScrub(geometry.timeAt(offset.x))
                    },
                )
            }
            .pointerInput(geometry, sequences) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = geometry.hitTest(sequences, offset.x, offset.y)
                            ?.let { DragState(it, offset.x) }
                    },
                    onDragEnd = {
                        dragging?.let { state ->
                            onMove(state.hit.sequenceIndex, state.hit.itemIndex, state.targetStartUs(geometry))
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onDrag = { change, amount ->
                        change.consume()
                        dragging = dragging?.let { it.copy(currentX = it.currentX + amount.x) }
                            ?: run { onScrub(geometry.timeAt(change.position.x)); null }
                    },
                )
            },
    ) {
        drawRuler(geometry)
        sequences.forEachIndexed { index, sequence ->
            drawTrack(sequence, index, geometry, selectedClipId, dragging)
        }
        drawPlayhead(geometry.xAt(playheadUs), size)
    }
}

private data class DragState(val hit: TimelineGeometry.Hit, val currentX: Float) {
    fun targetStartUs(geometry: TimelineGeometry): Us =
        geometry.snap(geometry.timeAt(currentX))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRuler(
    geometry: TimelineGeometry,
) {
    val height = Tokens.Layout.RULER_HEIGHT_DP.dp.toPx()
    for (tick in geometry.ticks()) {
        val x = geometry.xAt(tick.atUs)
        drawLine(
            color = Color.White.copy(alpha = if (tick.major) 0.28f else 0.12f),
            start = Offset(x, height * if (tick.major) 0.35f else 0.6f),
            end = Offset(x, height),
            strokeWidth = 1f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(
    sequence: Sequence,
    trackIndex: Int,
    geometry: TimelineGeometry,
    selectedClipId: String?,
    dragging: DragState?,
) {
    val top = geometry.trackTop(trackIndex)
    val height = Tokens.Layout.TRACK_HEIGHT_DP.dp.toPx()
    val radius = androidx.compose.ui.geometry.CornerRadius(Tokens.Radius.SMALL.dp.toPx())
    val trackColor = Tokens.Track.all[trackIndex % Tokens.Track.all.size].toComposeColor()

    var cursorUs = 0L
    sequence.items.forEachIndexed { itemIndex, item ->
        val startUs = cursorUs
        cursorUs += item.durationUs

        when (item) {
            is Gap -> Unit // Gaten worden niet getekend; de leegte ís de weergave.

            is Clip -> {
                val beingDragged = dragging?.hit?.sequenceIndex == trackIndex &&
                    dragging.hit.itemIndex == itemIndex

                val x = if (beingDragged) dragging.currentX else geometry.xAt(startUs)
                val width = geometry.widthOf(item.durationUs)

                drawRoundRect(
                    color = trackColor.copy(alpha = if (beingDragged) 0.75f else 1f),
                    topLeft = Offset(x, top),
                    size = Size(width, height),
                    cornerRadius = radius,
                )

                if (item.id == selectedClipId) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(x, top),
                        size = Size(width, height),
                        cornerRadius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayhead(x: Float, size: Size) {
    drawLine(
        color = Color.White,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = Tokens.Layout.PLAYHEAD_WIDTH_DP.toFloat(),
    )
    drawCircle(color = Color.White, radius = 5f, center = Offset(x, 0f))
}

private fun nl.artifation.videoeditor.design.Argb.toComposeColor(): Color = Color(value)

/** Compose-loze density-hulp voor [TimelineGeometry]; zie daar. */
internal fun Density.pxPerDp(): Float = 1.dp.toPx()
