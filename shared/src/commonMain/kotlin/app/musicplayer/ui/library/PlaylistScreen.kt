package app.musicplayer.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(
    id: PlaylistId,
    model: LibraryModel,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onRename: (PlaylistDetail) -> Unit,
    onDelete: (PlaylistId) -> Unit,
    onMenu: (Track) -> Unit,
) {
    val flow = remember(id, model) { model.playlist(id) }
    val state by flow.collectAsState(LoadState(loading = true))
    val detail = state.data
    var menu by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(detail?.name ?: "Плейлист", onBack) {
            Box {
                IconButton({ menu = true }, enabled = detail != null) {
                    Icon(AppIcons.More, "Действия с плейлистом")
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(AppIcons.Edit, null, Modifier.size(24.dp)) },
                        text = { Text("Переименовать") },
                        onClick = {
                            menu = false
                            detail?.let(onRename)
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(AppIcons.Trash, null, Modifier.size(24.dp)) },
                        text = { Text("Удалить плейлист") },
                        onClick = {
                            menu = false
                            onDelete(id)
                        },
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (detail != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlaylistCollage(
                            detail.items.map { it.track.artwork }.distinct().take(4),
                            artwork,
                            Modifier.size(180.dp),
                        )
                        Text(
                            trackCount(detail.items.size),
                            Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(detail.items, key = { _, item -> item.id.value }) { index, item ->
                    var actions by remember(item.id) { mutableStateOf(false) }
                    TrackRow(
                        item.track,
                        artwork,
                        { model.play(detail.items.map { it.track }, index) },
                        { onMenu(item.track) },
                        modifier =
                            Modifier.semantics {
                                customActions =
                                    listOfNotNull(
                                        if (index > 0)
                                            CustomAccessibilityAction("Выше") {
                                                scope.launch { model.move(id, item.id, index - 1) }
                                                true
                                            }
                                        else null,
                                        if (index < detail.items.lastIndex)
                                            CustomAccessibilityAction("Ниже") {
                                                scope.launch { model.move(id, item.id, index + 1) }
                                                true
                                            }
                                        else null,
                                        CustomAccessibilityAction("Убрать из плейлиста") {
                                            scope.launch { model.remove(id, item.id) }
                                            true
                                        },
                                    )
                            },
                        trailing = {
                            ReorderHandle(item.id.value, index, detail.items.lastIndex) { target ->
                                scope.launch { model.move(id, item.id, target) }
                            }
                            Box {
                                IconButton({ actions = true }) {
                                    Icon(AppIcons.More, "Действия с записью плейлиста")
                                }
                                DropdownMenu(actions, { actions = false }) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(AppIcons.More, null, Modifier.size(24.dp))
                                        },
                                        text = { Text("Действия с треком") },
                                        onClick = {
                                            actions = false
                                            onMenu(item.track)
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(AppIcons.Trash, null, Modifier.size(24.dp))
                                        },
                                        text = { Text("Убрать из плейлиста") },
                                        onClick = {
                                            actions = false
                                            scope.launch { model.remove(id, item.id) }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                if (detail.items.isEmpty())
                    item {
                        Text(
                            "Добавляйте треки через меню поиска или плеера",
                            Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            } else if (!state.loading && state.error == null) item { Text("Этот плейлист удалён") }
            state.error?.let { item { InlineError(it) } }
        }
    }
}
