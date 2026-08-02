package nl.artifation.videoeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us

/**
 * Het voorstel van de stilte-analyse, met een knop om het te accepteren.
 *
 * Een voorstel en geen automatische bewerking: de analyse weet wáár het stil is,
 * maar niet of die stilte ergens voor stond. Een adempauze voor een clou is
 * technisch stilte en inhoudelijk timing. Daarom beslist de gebruiker, en is
 * "Later" net zo bereikbaar als "Toepassen".
 *
 * Het toepassen zelf gaat door de gewone ongedaan-maken-stapel, dus een verkeerd
 * ja is één druk op ↶ terug.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README.
 */
@Composable
public fun ProposalSheet(
    proposal: SilenceProposal,
    onApply: () -> Unit,
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
        Text(
            text = "${proposal.silenceCount} ${stiltes(proposal.silenceCount)} gevonden",
            color = Tokens.Palette.textPrimary.toColor(),
            fontSize = Tokens.Type.Body.sizeSp.sp,
            fontWeight = FontWeight.Medium,
        )

        // De uitkomst en niet de meting: "2:31 korter" is een reden om ja te
        // zeggen, "zeventien intervallen" is dat niet.
        Text(
            text = "${duur(proposal.removedUs)} korter — blijft ${duur(proposal.resultingDurationUs)} over",
            color = Tokens.Palette.textSecondary.toColor(),
            fontSize = Tokens.Type.Label.sizeSp.sp,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.M.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Toepassen",
                color = Color.White,
                fontSize = Tokens.Type.Label.sizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                    .background(Tokens.Palette.accent.toColor())
                    .clickable(onClick = onApply)
                    .padding(horizontal = Tokens.Space.L.dp, vertical = Tokens.Space.S.dp),
            )

            Text(
                text = "Later",
                color = Tokens.Palette.textSecondary.toColor(),
                fontSize = Tokens.Type.Label.sizeSp.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Radius.FULL.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = Tokens.Space.M.dp, vertical = Tokens.Space.S.dp),
            )
        }
    }
}

private fun stiltes(aantal: Int): String = if (aantal == 1) "stilte" else "stiltes"

/** Minuten en seconden; uren komen bij dit materiaal niet voor. */
private fun duur(us: Us): String {
    val totaleSeconden = us / US_PER_SECOND
    return "%d:%02d".format(totaleSeconden / SECONDEN_PER_MINUUT, totaleSeconden % SECONDEN_PER_MINUUT)
}

private const val SECONDEN_PER_MINUUT = 60L
