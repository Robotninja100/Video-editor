package nl.artifation.videoeditor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.toRenderPlan
import nl.artifation.videoeditor.render.toComposition

/**
 * De preview-speler voor het project op het scherm.
 *
 * Eén speler voor de hele levensduur van het scherm; bij elke wijziging van het
 * project krijgt hij een nieuwe `Composition`. Opnieuw opbouwen bij elke
 * bewerking zou de decoders per knip opnieuw laten opstarten en het monteren
 * onbruikbaar traag maken.
 *
 * De vertaling van project naar `Composition` gebeurt via `toRenderPlan()` en
 * `toComposition()` en nergens anders — zie het ontwerpprincipe in de README.
 *
 * **Nooit op een toestel gedraaid.** `CompositionPlayer` is `@ExperimentalApi`;
 * dat dit werkt, is precies wat fase 0 op het toestel moet uitwijzen.
 */
@UnstableApi
@Composable
public fun rememberPreviewPlayer(project: Project): Player? {
    val context: Context = LocalContext.current

    val player = remember { CompositionPlayer.Builder(context).build() }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Een leeg project heeft niets af te spelen, en `Composition.Builder` eist
    // minstens één sequence met inhoud. Er wordt daarom niets gezet én niets
    // teruggegeven; `PlayerSurface` toont dan gewoon de achtergrond.
    //
    // De guard staat bewust hier en niet als vroege `return` bovenaan: elke
    // `remember` en elke `DisposableEffect` moet onvoorwaardelijk aangeroepen
    // worden, anders verschuiven de compositiegroepen zodra het project van leeg
    // naar gevuld gaat en verliest de speler zijn identiteit.
    val hasContent = project.sequences.any { it.items.isNotEmpty() }

    LaunchedEffect(project, hasContent) {
        if (hasContent) {
            player.setComposition(project.toRenderPlan().toComposition(context))
            player.prepare()
        }
    }

    return player.takeIf { hasContent }
}
