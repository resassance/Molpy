package app.musicplayer.ui.player

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.PlayerStatus
import app.musicplayer.ui.components.*

@Composable
fun MiniPlayer(
    state: PlayerState,
    artwork: ArtworkLoader,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val track = state.currentTrack ?: return
    val color = MaterialTheme.colorScheme.primary
    val open by rememberUpdatedState(onOpen)
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        if (distance < -24.dp.toPx()) open()
                        distance = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    distance += amount
                }
            }
            .testTag("mini-player")
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(track.artwork, artwork, Modifier.size(40.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        track.artists.joinToString { it.name },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val active =
                state.snapshot.status == PlayerStatus.PLAYING || state.snapshot.playWhenReady
            IconButton(onPlayPause, Modifier.size(48.dp)) {
                Icon(
                    if (active) AppIcons.Pause else AppIcons.Play,
                    if (active) "Пауза" else "Продолжить",
                )
            }
            IconButton(onNext, Modifier.size(48.dp)) { Icon(AppIcons.Next, "Следующий трек") }
        }
        val duration = state.snapshot.durationMs?.takeIf { it > 0 }
        val fraction =
            if (duration == null) 0f
            else (state.snapshot.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(color.copy(alpha = 0.25f))
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
    }
}
