package app.musicplayer.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.components.TrackArtwork

internal data class TrackVisuals(
    val entryId: QueueEntryId?,
    val track: Track,
    val previousArtwork: Artwork?,
    val nextArtwork: Artwork?,
) {
    val key: Any
        get() = entryId ?: track.id
}

@Composable
internal fun TrackChangeContent(
    target: TrackVisuals,
    direction: TrackMotion,
    modifier: Modifier = Modifier,
    content: @Composable (TrackVisuals, Boolean) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        contentKey = { it.key },
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val sign =
                when (direction) {
                    TrackMotion.NEXT -> 1
                    TrackMotion.PREVIOUS -> -1
                    TrackMotion.REPLACE -> 0
                }
            ((fadeIn(tween(220)) + slideInHorizontally(tween(220)) { sign * it / 6 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { -sign * it / 6 }))
                .using(null)
        },
        label = "track-change",
    ) { shown ->
        content(shown, shown.key == target.key)
    }
}

@Composable
internal fun TrackArtworkStage(
    target: TrackVisuals,
    direction: TrackMotion,
    artwork: ArtworkLoader,
    cover: Dp,
    modifier: Modifier = Modifier,
) {
    TrackChangeContent(target, direction, modifier.fillMaxWidth().height(cover + 16.dp)) { shown, _
        ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            shown.previousArtwork?.let { previous ->
                TrackArtwork(
                    previous,
                    artwork,
                    Modifier.offset(x = -(cover * 0.2f))
                        .size(cover * 0.8f)
                        .alpha(0.30f)
                        .clearAndSetSemantics {},
                )
            }
            shown.nextArtwork?.let { next ->
                TrackArtwork(
                    next,
                    artwork,
                    Modifier.offset(x = cover * 0.2f)
                        .size(cover * 0.8f)
                        .alpha(0.30f)
                        .clearAndSetSemantics {},
                )
            }
            TrackArtwork(
                shown.track.artwork,
                artwork,
                Modifier.size(cover),
            )
        }
    }
}
