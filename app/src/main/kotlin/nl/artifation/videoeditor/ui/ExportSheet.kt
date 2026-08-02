package nl.artifation.videoeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.artifation.videoeditor.design.Tokens

/**
 * Wat er tijdens en na een export te zien is.
 *
 * Dezelfde vorm als [AnalysisSheet], want het is voor de gebruiker hetzelfde
 * soort moment: iets duurt lang en je wilt weten waar het staat en hoe je ervan
 * af komt.
 *
 * Eén verschil dat er wel toe doet: een afgeronde export blijft staan tot je hem
 * wegklikt. Een paneel dat vanzelf verdwijnt laat je twijfelen of het gelukt is,
 * en waar het bestand dan wel niet staat.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README.
 */
@Composable
public fun ExportSheet(
    status: ExportStatus,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(Tokens.Space.M.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.SHEET.dp))
            .background(Color.Black.copy(alpha = Tokens.GlassLevel.Overlay.scrimAlpha))
            .padding(Tokens.Space.L.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.M.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (status) {
                    is ExportStatus.Running -> "Exporteren… ${status.percent}%"
                    is ExportStatus.Done -> "Klaar"
                    is ExportStatus.Failed -> "Export mislukt"
                },
                color = Tokens.Palette.textPrimary.toColor(),
                fontSize = Tokens.Type.Body.sizeSp.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            // Tijdens het werk stoppen, daarna sluiten. Eén knop op één plek, met
            // het woord dat op dat moment klopt.
            Text(
                text = if (status is ExportStatus.Running) "Stoppen" else "Sluiten",
                color = Tokens.Palette.accent.toColor(),
                fontSize = Tokens.Type.Label.sizeSp.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                    .clickable(onClick = if (status is ExportStatus.Running) onCancel else onDismiss)
                    .padding(horizontal = Tokens.Space.M.dp, vertical = Tokens.Space.S.dp),
            )
        }

        if (status is ExportStatus.Running) {
            Voortgangsbalk(status.percent / PERCENT_VOLLEDIG)
        }

        val toelichting = when (status) {
            is ExportStatus.Running -> null
            // Het pad erbij: zonder dat is "klaar" een mededeling waar je niets
            // mee kunt, want het bestand staat niet in de galerij.
            is ExportStatus.Done -> status.outputPath
            is ExportStatus.Failed -> status.message
        }

        toelichting?.let { tekst ->
            Text(
                text = tekst,
                color = Tokens.Palette.textSecondary.toColor(),
                fontSize = Tokens.Type.Label.sizeSp.sp,
            )
        }
    }
}

@Composable
private fun Voortgangsbalk(fractie: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Space.XS.dp)
            .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
            .background(Tokens.Palette.textPrimary.withAlpha(BALK_ACHTERGROND_ALPHA).toColor()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fractie.coerceIn(0f, 1f))
                .height(Tokens.Space.XS.dp)
                .background(Tokens.Palette.accent.toColor()),
        )
    }
}

private const val PERCENT_VOLLEDIG = 100f
private const val BALK_ACHTERGROND_ALPHA = 0.16f
