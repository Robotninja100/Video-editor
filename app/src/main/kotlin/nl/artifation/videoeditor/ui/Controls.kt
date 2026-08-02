package nl.artifation.videoeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.artifation.videoeditor.design.Tokens

/** Kleur uit het ontwerpsysteem naar Compose; nergens anders wordt kleur bedacht. */
internal fun nl.artifation.videoeditor.design.Argb.toColor(): Color = Color(value)

/**
 * De bovenbalk: waar je bent, en wat je ermee doet.
 *
 * **Niet gecompileerd.** Zie README: `:app` staat nog niet in de build.
 */
@Composable
public fun Toolbar(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = Tokens.Space.M.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.M.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.project.id,
                color = Tokens.Palette.textPrimary.toColor(),
                fontSize = Tokens.Type.Label.sizeSp.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${state.project.outputSpec.width} × ${state.project.outputSpec.height}" +
                    " · ${state.project.outputSpec.frameRate} fps",
                color = Tokens.Palette.textTertiary.toColor(),
                fontSize = Tokens.Type.Mono.sizeSp.sp,
            )
        }

        // Knippen op de playhead is bij monteren de handeling die je het vaakst
        // doet, dus die staat in de balk en niet achter een menu.
        BalkKnop("✂", enabled = true, onClick = actions.onSplit)
        BalkKnop("⌫", enabled = state.canDelete, onClick = actions.onDelete)

        // Ongedaan maken hoort er direct naast: knippen is de handeling die je
        // het vaakst terugdraait.
        BalkKnop("↶", state.canUndo, actions.onUndo)
        BalkKnop("↷", state.canRedo, actions.onRedo)

        val exporteert = state.export is ExportStatus.Running
        Text(
            text = if (exporteert) "Bezig…" else "Exporteer",
            color = Color.White,
            fontSize = Tokens.Type.Label.sizeSp.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                .background(
                    if (exporteert) {
                        Tokens.Palette.accent.withAlpha(UITGEGRIJSD_ALPHA).toColor()
                    } else {
                        Tokens.Palette.accent.toColor()
                    },
                )
                // Tijdens een lopende export niet klikbaar: twee keer starten zou
                // op een `check` in de Exporter stuklopen.
                .clickable(enabled = !exporteert, onClick = actions.onExport)
                .padding(horizontal = Tokens.Space.L.dp, vertical = Tokens.Space.S.dp),
        )
    }
}

private const val UITGEGRIJSD_ALPHA = 0.4f

/**
 * Een knop die uitgegrijsd is wanneer er niets te doen valt.
 *
 * Uitgrijzen en niet verbergen: een knop die verspringt maakt de balk
 * onvoorspelbaar, en je duim leert de plek af.
 */
@Composable
private fun BalkKnop(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        color = if (enabled) {
            Tokens.Palette.textPrimary.toColor()
        } else {
            Tokens.Palette.textTertiary.toColor()
        },
        fontSize = Tokens.Type.Headline.sizeSp.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(Tokens.Layout.MIN_TOUCH_DP.dp)
            .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * De gereedschapskolom naast het beeld.
 *
 * Elk doel is minstens [Tokens.Layout.MIN_TOUCH_DP] groot — kleiner is met een
 * duim op een rijdende trein niet te raken, en dat staat ook in een test.
 */
@Composable
public fun ToolRail(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    // Alleen het audiogereedschap doet iets: dat is fase 2 en die is af. De rest
    // staat er al wel, zodat de kolom niet bij elke fase van vorm verandert.
    val tools = listOf(
        Tool("◎", Tokens.Track.mask, null),
        Tool("T", Tokens.Track.caption, null),
        Tool("⌗", Tokens.Track.video, null),
        Tool("◐", Tokens.Track.audio, actions.onAnalyzeAudio),
    )

    Column(
        modifier = modifier.padding(Tokens.Space.XS.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.XS.dp),
    ) {
        for (tool in tools) {
            val actief = tool.onClick != null && state.analysis == null
            Text(
                text = tool.glyph,
                color = if (actief) {
                    tool.kleur.toColor()
                } else {
                    tool.kleur.withAlpha(UITGEGRIJSD_ALPHA).toColor()
                },
                fontSize = Tokens.Type.Headline.sizeSp.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(Tokens.Layout.MIN_TOUCH_DP.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                    .clickable(enabled = actief) { tool.onClick?.invoke() }
                    .padding(Tokens.Space.S.dp),
            )
        }
    }
}

private data class Tool(
    val glyph: String,
    val kleur: nl.artifation.videoeditor.design.Argb,
    /** `null` betekent: dit gereedschap bestaat nog niet. */
    val onClick: (() -> Unit)?,
)
