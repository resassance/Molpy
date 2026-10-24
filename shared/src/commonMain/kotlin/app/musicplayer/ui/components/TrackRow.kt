package app.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.Track

fun formatTrackDuration(value: Long?): String {
    if (value == null || value < 0) return "—"
    val seconds = value / 1000
    return if (seconds >= 3600)
        "${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}"
    else "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
}

@Composable
fun TrackRow(
    track: Track,
    artwork: ArtworkLoader,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    trailingLabel: String? = null,
    showAlbum: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.2f
        val time = trailingLabel ?: formatTrackDuration(track.durationMs?.takeIf { it > 0 })
        Row(
            modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onPlay)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(track.artwork, artwork, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    trackPresentation(track)
                        .artists
                        .joinToString { it.name }
                        .ifBlank { "Исполнитель не указан" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                track.album
                    ?.takeIf { showAlbum }
                    ?.let {
                        Text(
                            it.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                if (compact)
                    Text(
                        time,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (trailingLabel != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (!compact)
                Text(
                    time,
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (trailingLabel != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (trailing != null) trailing()
            else
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(
                        AppIcons.More,
                        "Действия с треком",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}
