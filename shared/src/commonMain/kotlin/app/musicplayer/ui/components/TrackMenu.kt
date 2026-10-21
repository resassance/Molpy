package app.musicplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(
    track: Track,
    favorite: Boolean,
    canDownload: Boolean,
    onFavorite: () -> Unit,
    onEnqueue: () -> Unit,
    onPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onDownloads: () -> Unit,
    hasDownload: Boolean,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(track.title, style = MaterialTheme.typography.titleLarge)
            Text(
                track.artists.joinToString { it.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onFavorite, Modifier.fillMaxWidth()) {
                Icon(if (favorite) AppIcons.HeartFilled else AppIcons.Heart, null)
                Spacer(Modifier.width(12.dp))
                Text(if (favorite) "Убрать из избранного" else "В избранное")
            }
            TextButton(onEnqueue, Modifier.fillMaxWidth()) { Text("Добавить в очередь") }
            TextButton(onPlaylist, Modifier.fillMaxWidth()) { Text("Добавить в плейлист") }
            if (hasDownload)
                TextButton(onDownloads, Modifier.fillMaxWidth()) { Text("Открыть загрузки") }
            else {
                TextButton(onDownload, Modifier.fillMaxWidth(), enabled = canDownload) {
                    Icon(AppIcons.Download, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Скачать на устройство")
                }
                if (!canDownload)
                    Text(
                        "Сначала воспроизведите трек",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}
