package nl.artifation.videoeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import nl.artifation.videoeditor.design.Tokens
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.TimelineGeometry
import nl.artifation.videoeditor.model.Us

/**
 * Het editorscherm.
 *
 * De opbouw volgt uit één beslissing: een video-editor toont beeld, dus de video
 * vult het scherm en alles eromheen ligt er als glas overheen. De preview wordt
 * nooit verkleind om plaats te maken voor chroom.
 *
 * **Niet gecompileerd.** Geen Android SDK beschikbaar; module staat nog niet in
 * `settings.gradle.kts`.
 */
@Composable
public fun EditorScreen(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val geometry = with(density) {
        TimelineGeometry(
            // Klemmen, niet doorgeven. `TimelineGeometry` eist pxPerSecond > 0,
            // en de natuurlijke beginwaarde "nog niet ingezoomd" is nul — dat
            // zou een IllegalArgumentException vanuit de compositie gooien en
            // het hele editorscherm slopen in plaats van iets bruikbaars te tonen.
            pxPerSecond = state.pxPerSecond.coerceIn(
                TimelineGeometry.MIN_PX_PER_SECOND,
                TimelineGeometry.MAX_PX_PER_SECOND,
            ),
            scrollPx = state.scrollPx,
            rulerHeightPx = Tokens.Layout.RULER_HEIGHT_DP.dp.toPx(),
            trackHeightPx = Tokens.Layout.TRACK_HEIGHT_DP.dp.toPx(),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(Tokens.Palette.backdrop.value)),
    ) {
        // De preview ligt onderop en vult alles.
        PlayerSurface(
            player = state.player,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {

            GlassSurface(
                level = Tokens.GlassLevel.Floating,
                shape = RoundedCornerShape(Tokens.Radius.MEDIUM.dp),
                modifier = Modifier
                    .padding(Tokens.Space.M.dp)
                    .fillMaxWidth()
                    .height(Tokens.Layout.TOOLBAR_HEIGHT_DP.dp),
            ) {
                Toolbar(state, actions, Modifier.align(Alignment.Center))
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Tokens.Space.M.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                GlassSurface(
                    level = Tokens.GlassLevel.Floating,
                    shape = RoundedCornerShape(Tokens.Radius.FULL.dp),
                ) {
                    ToolRail(state, actions)
                }
            }

            // De tijdlijn is Recessed: hij is grotendeels achtergrond en mag de
            // aandacht niet wegtrekken van het beeld.
            GlassSurface(
                level = Tokens.GlassLevel.Recessed,
                shape = RoundedCornerShape(Tokens.Radius.LARGE.dp),
                modifier = Modifier
                    .padding(Tokens.Space.S.dp)
                    .fillMaxWidth()
                    .height(Tokens.Layout.TIMELINE_HEIGHT_DP.dp),
            ) {
                Timeline(
                    sequences = state.project.sequences,
                    playheadUs = state.playheadUs,
                    selectedClipId = state.selectedClipId,
                    geometry = geometry,
                    onScrub = actions.onScrub,
                    onSelect = actions.onSelect,
                    onMove = actions.onMove,
                    onZoom = actions.onZoom,
                )
            }
        }

        if (state.analysis != null) {
            // Overlay: analyse duurt minuten en verdient de aandacht die het opeist.
            AnalysisSheet(
                progress = state.analysis,
                onCancel = actions.onCancelAnalysis,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

public data class EditorState(
    val project: Project,
    val playheadUs: Us,
    val selectedClipId: String?,
    val pxPerSecond: Float,
    val scrollPx: Float,
    val player: Any?,
    val analysis: AnalysisProgress?,
)

public data class AnalysisProgress(
    val label: String,
    val fraction: Float,
    /** Geschatte kosten bij cloud-analyse; vooraf tonen, niet achteraf. */
    val estimatedUsd: Double?,
)

public data class EditorActions(
    val onScrub: (Us) -> Unit,
    val onSelect: (String?) -> Unit,
    val onMove: (sequenceIndex: Int, itemIndex: Int, targetStartUs: Us) -> Unit,
    val onZoom: (Float) -> Unit,
    val onCancelAnalysis: () -> Unit,
)
