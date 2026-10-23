package app.musicplayer.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.downloads.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.present
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    model: DownloadsModel,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onManageStorage: () -> Unit,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val available = state.assets.filter { it.state == DownloadState.AVAILABLE }
    var info by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Загрузки", onBack)
        LazyColumn(
            Modifier.weight(1f),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    "${trackCount(available.size)} · ${formatBytes(state.usage.totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { item { InlineError(it, model::refreshStorage) } }
            val pending = state.assets.filter { it.state != DownloadState.AVAILABLE }
            if (pending.isNotEmpty()) item { SectionHeader("Загружается") }
            items(pending, key = { it.id.value }) { asset ->
                DownloadRow(
                    asset,
                    state,
                    artwork,
                    {},
                    {
                        scope.launch {
                            if (asset.state == DownloadState.DOWNLOADING) model.pause(asset.id)
                            else model.resume(asset.id)
                        }
                    },
                    { scope.launch { model.cancel(asset.id) } },
                    { scope.launch { model.remove(asset.id) } },
                    onManageStorage,
                )
            }
            item {
                if (pending.isNotEmpty())
                    HorizontalDivider(
                        Modifier.padding(top = 24.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                SectionHeader("На устройстве")
            }
            itemsIndexed(available, key = { _, asset -> asset.id.value }) { index, asset ->
                DownloadRow(
                    asset,
                    state,
                    artwork,
                    { onPlay(available.map { it.trackSnapshot }, index) },
                    {},
                    {},
                    { scope.launch { model.remove(asset.id) } },
                    onManageStorage,
                )
            }
            if (available.isEmpty())
                item {
                    Text(
                        "Скачанная музыка появится здесь",
                        Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            item { TextButton({ info = true }) { Text("О хранилище") } }
        }
    }
    if (info)
        MolpyDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton({ info = false }) { Text("Понятно") } },
            title = "Музыка на устройстве",
            icon = AppIcons.Storage,
            text = {
                Text(
                    "Треки хранятся в данных приложения. При удалении Molpy или очистке его данных скачанная музыка удалится."
                )
            },
        )
}

@Composable
private fun DownloadRow(
    asset: OfflineAsset,
    state: DownloadsState,
    artwork: ArtworkLoader,
    onPlay: () -> Unit,
    onResumePause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onManageStorage: () -> Unit,
) {
    val track = asset.trackSnapshot
    var menu by remember(asset.id) { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = asset.state == DownloadState.AVAILABLE, onClick = onPlay),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(track.artwork, artwork, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    trackPresentation(track).artists.joinToString { it.name },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val progress = downloadProgress(asset)
                val label =
                    when (asset.state) {
                        DownloadState.AVAILABLE ->
                            "${formatTrackDuration(track.durationMs)} · ${formatBytes(state.usage.perAssetBytes[asset.id] ?: asset.bytes)}"
                        DownloadState.QUEUED -> "В очереди"
                        DownloadState.PAUSED -> "На паузе · ${formatBytes(asset.bytes)}"
                        DownloadState.FAILED -> "Не удалось скачать"
                        DownloadState.DOWNLOADING ->
                            if (progress == null) formatBytes(asset.bytes)
                            else
                                "${formatBytes(asset.bytes)} из ${formatBytes(asset.representation.expectedBytes!!)} · ${(progress*100).toInt()}%"
                    }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (asset.state == DownloadState.DOWNLOADING && progress != null)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
            }
            if (
                asset.state in
                    setOf(DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.FAILED)
            )
                IconButton(onResumePause, enabled = asset.id !in state.busy) {
                    Icon(
                        if (asset.state == DownloadState.DOWNLOADING) AppIcons.Pause
                        else AppIcons.Retry,
                        if (asset.state == DownloadState.DOWNLOADING) "Приостановить загрузку"
                        else "Продолжить загрузку",
                    )
                }
            Box {
                IconButton({ menu = true }) { Icon(AppIcons.More, "Действия с загрузкой") }
                DropdownMenu(menu, { menu = false }) {
                    if (
                        asset.state == DownloadState.AVAILABLE ||
                            asset.state == DownloadState.FAILED
                    )
                        DropdownMenuItem(
                            leadingIcon = { Icon(AppIcons.Trash, null, Modifier.size(24.dp)) },
                            text = { Text("Удалить с устройства") },
                            onClick = {
                                menu = false
                                onRemove()
                            },
                        )
                    else
                        DropdownMenuItem(
                            leadingIcon = { Icon(AppIcons.Close, null, Modifier.size(24.dp)) },
                            text = { Text("Отменить загрузку") },
                            onClick = {
                                menu = false
                                onCancel()
                            },
                        )
                }
            }
        }
        state.failures[asset.id]?.let { error ->
            Text(
                present(error).message,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error.code == ErrorCode.STORAGE_FULL)
                TextButton(onManageStorage) { Text("Открыть хранилище") }
        }
    }
}
