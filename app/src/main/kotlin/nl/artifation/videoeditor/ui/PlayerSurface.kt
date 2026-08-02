package nl.artifation.videoeditor.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import nl.artifation.videoeditor.design.Tokens

/**
 * Het beeld waar alles overheen ligt.
 *
 * Een `SurfaceView` en geen `TextureView`: die laatste gaat door de
 * view-hiërarchie en kost bij 4K merkbaar meer, terwijl hij hier niets
 * toevoegt — er wordt niet geroteerd of getransformeerd op view-niveau.
 *
 * Zonder speler blijft het vlak de achtergrondkleur. Dat is de toestand bij het
 * opstarten en na het sluiten van een project, en die hoort niet zwart-met-rand
 * te zijn maar gewoon de achtergrond.
 *
 * **Niet gecompileerd.** Zie README: `:app` staat nog niet in de build.
 */
@UnstableApi
@Composable
public fun PlayerSurface(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    if (player == null) {
        Box(modifier.background(Color(Tokens.Palette.backdrop.value)))
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { context -> SurfaceView(context) },
        onRelease = { player.clearVideoSurface() },
        update = { view -> player.setVideoSurfaceView(view) },
    )

    DisposableEffect(player) {
        onDispose { player.clearVideoSurface() }
    }
}
