package app.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.musicplayer.ui.components.formatTrackDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackProgress(
    positionMs: Long,
    durationMs: Long?,
    bufferedPositionMs: Long?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val duration = durationMs?.takeIf { it > 0 }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val position = scrub ?: positionMs.toFloat()
    Column(modifier) {
        Slider(
            value = position.coerceIn(0f, (duration ?: 1).toFloat()),
            onValueChange = { scrub = it },
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = "Позиция воспроизведения"
                },
            enabled = duration != null,
            valueRange = 0f..(duration ?: 1).toFloat(),
            onValueChangeFinished = {
                scrub?.let { onSeek(it.toLong()) }
                scrub = null
            },
            thumb = {
                // Material3 imposes a minimum slot height; center the smaller circle within it.
                Box(Modifier.width(12.dp).height(16.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).background(colors.primary, CircleShape))
                }
            },
            track = { slider ->
                Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(4.dp)
                            .background(colors.onSurface.copy(alpha = 0.25f), CircleShape)
                    ) {
                        val buffered =
                            if (duration == null) 0f
                            else
                                ((bufferedPositionMs ?: 0).toDouble() / duration)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                        val played =
                            if (duration == null) 0f
                            else (slider.value / duration.toFloat()).coerceIn(0f, 1f)
                        Box(
                            Modifier.fillMaxWidth(buffered)
                                .fillMaxHeight()
                                .background(colors.primary.copy(alpha = 0.35f), CircleShape)
                        )
                        Box(
                            Modifier.fillMaxWidth(played)
                                .fillMaxHeight()
                                .background(colors.primary, CircleShape)
                        )
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTrackDuration(position.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                formatTrackDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
