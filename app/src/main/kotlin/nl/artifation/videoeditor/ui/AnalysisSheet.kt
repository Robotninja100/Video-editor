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
 * Wat er tijdens de analyse te zien is.
 *
 * `Overlay`-glas: analyse duurt minuten en verdient die aandacht. Het paneel
 * toont altijd een **reden** naast de voortgang — een balk die stilstaat zonder
 * uitleg leest als een vastgelopen app, en dat is precies waar het thermische
 * beleid in `:core-thermal` een tekst voor meelevert.
 *
 * **Niet gecompileerd.** Zie README: `:app` staat nog niet in de build.
 */
@Composable
public fun AnalysisSheet(
    progress: AnalysisProgress,
    onCancel: () -> Unit,
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
                text = progress.label,
                color = Tokens.Palette.textPrimary.toColor(),
                fontSize = Tokens.Type.Body.sizeSp.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Stoppen",
                color = Tokens.Palette.accent.toColor(),
                fontSize = Tokens.Type.Label.sizeSp.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = Tokens.Space.M.dp, vertical = Tokens.Space.S.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Tokens.Space.XS.dp)
                .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                .background(Tokens.Palette.textPrimary.withAlpha(0.16f).toColor()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                    .height(Tokens.Space.XS.dp)
                    .background(Tokens.Palette.accent.toColor()),
            )
        }

        // De kosten vooraf tonen, niet achteraf: segmentatie wordt per frame
        // betaald en dat hoort geen verrassing te zijn.
        progress.estimatedUsd?.let { kosten ->
            Text(
                text = "Geschatte kosten: %.2f dollar".format(kosten),
                color = Tokens.Palette.textSecondary.toColor(),
                fontSize = Tokens.Type.Label.sizeSp.sp,
            )
        }
    }
}
