package app.musicplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.musicplayer.model.*
import app.musicplayer.ui.components.LocalArtworkEpoch

internal val LocalArtworkSampler =
    staticCompositionLocalOf<(suspend (Artwork) -> AppResult<IntArray>)?> { null }

@Composable
internal fun rememberArtworkColors(artwork: Artwork?): ArtworkColors {
    val sample =
        LocalArtworkSampler.current ?: return remember { ArtworkPalette.fromPixels(intArrayOf()) }
    val scope = rememberCoroutineScope()
    val model = remember(sample, scope) { PaletteModel(sample, scope) }
    val epoch = LocalArtworkEpoch.current
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model, artwork, epoch) { model.update(artwork) }
    return model.state.collectAsState().value
}

@Composable
internal fun ArtworkAmbientBackground(colors: ArtworkColors, modifier: Modifier = Modifier) {
    val top =
        animateColorAsState(
            lerp(Color(colors.background), Color(colors.accent), 0.18f),
            tween(360),
            label = "artwork-top",
        )
    val middle = animateColorAsState(Color(colors.surface), tween(360), label = "artwork-middle")
    val bottom = animateColorAsState(Color(colors.background), tween(360), label = "artwork-bottom")
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to top.value,
                    0.52f to middle.value,
                    1f to bottom.value,
                    endY = size.height,
                )
            )
        }
    )
}
