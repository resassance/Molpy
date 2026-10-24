package app.musicplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.model.Track
import app.musicplayer.ui.theme.LocalAppTheme
import app.musicplayer.ui.theme.ThemeWindowAppearance

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
    onPlayNext: () -> Unit,
    onEditArtists: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ThemeWindowAppearance(LocalAppTheme.current)
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(track.title, style = MaterialTheme.typography.titleLarge)
            Text(
                trackPresentation(track).artists.joinToString { it.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            MenuAction(
                if (favorite) "Убрать из избранного" else "В избранное",
                if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                onFavorite,
            )
            onEditArtists?.let { MenuAction("Изменить исполнителей", AppIcons.Edit, it) }
            MenuAction("Воспроизвести следующим", AppIcons.QueueNext, onPlayNext)
            MenuAction("Добавить в конец очереди", AppIcons.QueueAdd, onEnqueue)
            MenuAction("Добавить в плейлист", AppIcons.PlaylistAdd, onPlaylist)
            if (hasDownload) MenuAction("Открыть загрузки", AppIcons.Download, onDownloads)
            else {
                MenuAction("Скачать на устройство", AppIcons.Download, onDownload, canDownload)
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
