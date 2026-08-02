package nl.artifation.videoeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import nl.artifation.videoeditor.design.Tokens

/**
 * De glaslagen uit het ontwerpsysteem, als Compose-component.
 *
 * Alle waarden komen uit `:core-design`; hier wordt niets verzonnen. Dat is wat
 * de interface samenhangend houdt in plaats van "elk scherm net iets anders".
 *
 * Volgorde is niet vrijblijvend: de scrim doet het leeswerk en gaat als eerste
 * over de achtergrond, daarna pas de glans. Andersom verlaagt de glans het
 * contrast dat de scrim net heeft opgebouwd.
 *
 * **Niet gecompileerd.** Geen Android SDK beschikbaar in de omgeving waarin dit
 * geschreven is; deze module staat daarom nog niet in `settings.gradle.kts`.
 */
@Composable
public fun GlassSurface(
    level: Tokens.GlassLevel,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Tokens.Radius.MEDIUM.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = level.scrimAlpha))
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = level.sheenAlpha),
                    0.46f to Color.Transparent,
                ),
            )
            .border(1.dp, Color.White.copy(alpha = level.borderAlpha), shape),
        content = content,
    )
}

/**
 * Open punt: echte backdrop-blur.
 *
 * `GlassSurface` hierboven levert scrim, glans en rand — dat werkt gegarandeerd
 * en draagt het contrast, want de leesbaarheid komt volledig van de scrim (zie
 * de tests in `:core-design`). De blur is puur materiaalgevoel.
 *
 * Wat *niet* werkt, en waar de voor de hand liggende oplossing stukloopt:
 * `Modifier.graphicsLayer { renderEffect = createBlurEffect(...) }` blurt de
 * **inhoud** van die laag, niet wat erachter ligt. Compose heeft geen CSS-achtige
 * `backdrop-filter`.
 *
 * De reële opties, in volgorde van voorkeur, te beslissen op het toestel:
 *
 * 1. Een backdrop-API in de Compose-versie die je vastpint — controleer bij het
 *    optuigen van `:app` of die er inmiddels is; dit schuift snel.
 * 2. De achtergrond zelf in een laag renderen en díe blurren met `Modifier.blur()`,
 *    waarna het paneel er bovenop komt. Werkt, maar kost een extra laag en je moet
 *    de begrenzing van het paneel doorgeven.
 * 3. `Window.setBackgroundBlurRadius()` (API 31+) — alleen voor vensters en
 *    dialogen, dus bruikbaar voor de `Overlay`-laag maar niet voor de tijdlijn.
 * 4. Een blur-bibliotheek van derden.
 *
 * Belangrijk: geen van deze keuzes raakt de leesbaarheid. Valt de blur weg, dan
 * ziet het er platter uit maar blijft alles even goed leesbaar. Daarom is dit
 * een afwerkingspunt en geen blokkade.
 */
internal object BackdropBlurNotes
