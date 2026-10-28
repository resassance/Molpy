package app.musicplayer.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.appearance.AppTheme
import app.musicplayer.model.*
import app.musicplayer.queue.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun DesktopScreen(graph: DesktopGraph) {
    val search by graph.search.state.collectAsState()
    val playback by graph.player.snapshot.collectAsState()
    val queue by graph.player.queue.collectAsState()
    var query by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf(AppTheme.DARK) }
    var queueVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = AppThemePalette.colors(theme, ArtworkPalette.fromPixels(intArrayOf()))
    MolpyTheme(theme, colors) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Molpy",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ menu = true }) {
                            Text(
                                when (theme) {
                                    AppTheme.DARK -> "Тёмная"
                                    AppTheme.CLASSIC -> "Классическая"
                                    AppTheme.LIGHT -> "Светлая"
                                }
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            AppTheme.entries.forEach { value ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (value) {
                                                AppTheme.DARK -> "Тёмная"
                                                AppTheme.CLASSIC -> "Классическая"
                                                AppTheme.LIGHT -> "Светлая"
                                            }
                                        )
                                    },
                                    onClick = {
                                        theme = value
                                        menu = false
                                    },
                                )
                            }
                        }
                    }
                    TextButton({ queueVisible = !queueVisible }) {
                        Text("Очередь · ${queue.entries.size}")
                    }
                }
                Row(
                    Modifier.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Название или исполнитель") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(onSearch = { graph.search.search(query) }),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button({ graph.search.search(query) }) { Text("Найти") }
                }
                if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                search.error?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Поиск недоступен: ${it.code}", Modifier.weight(1f))
                        TextButton(graph.search::retry) { Text("Повторить") }
                    }
                }
                Row(Modifier.weight(1f)) {
                    LazyColumn(Modifier.weight(1f)) {
                        val tracks = search.page?.tracks().orEmpty()
                        if (tracks.isEmpty() && !search.loading && search.error == null)
                            item {
                                Text(
                                    if (search.query.isBlank())
                                        "Найдите музыку по названию или исполнителю"
                                    else "Ничего не найдено",
                                    Modifier.padding(vertical = 24.dp),
                                )
                            }
                        items(tracks, key = { it.id.value }) { track ->
                            var actions by remember(track.id) { mutableStateOf(false) }
                            Box {
                                TrackRow(
                                    track,
                                    graph.artwork,
                                    {
                                        scope.launch {
                                            graph.player.playList(
                                                tracks,
                                                tracks.indexOfFirst { it.id == track.id },
                                            )
                                        }
                                    },
                                    { actions = true },
                                    selected = playback.track?.id == track.id,
                                )
                                DropdownMenu(actions, { actions = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Воспроизвести следующим") },
                                        onClick = {
                                            actions = false
                                            scope.launch {
                                                graph.player.enqueue(track, QueueInsertion.NEXT)
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Добавить в конец очереди") },
                                        onClick = {
                                            actions = false
                                            scope.launch {
                                                graph.player.enqueue(track, QueueInsertion.END)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        if (search.page?.next != null)
                            item {
                                if (search.loadingMore)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                else
                                    TextButton(
                                        if (search.moreError != null) graph.search::retryMore
                                        else graph.search::more
                                    ) {
                                        Text(
                                            if (search.moreError != null) "Повторить загрузку"
                                            else "Показать ещё"
                                        )
                                    }
                            }
                    }
                    if (queueVisible) {
                        Spacer(Modifier.width(16.dp))
                        LazyColumn(Modifier.widthIn(max = 260.dp).fillMaxWidth(0.3f)) {
                            item { Text("Очередь", style = MaterialTheme.typography.titleMedium) }
                            items(queue.entries, key = { it.id.value }) { entry ->
                                val track = graph.player.track(entry.trackId)
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                graph.player.dispatch(QueueCommand.Select(entry.id))
                                            }
                                        }
                                        .padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        track?.title ?: "Трек",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color =
                                            if (entry.id == queue.currentEntryId)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        track?.artists?.joinToString { it.name }.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                playback.track?.let { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackArtwork(track.artwork, graph.artwork, Modifier.size(64.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                track.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val duration = playback.durationMs ?: track.durationMs
                    var seeking by remember(track.id) { mutableStateOf<Float?>(null) }
                    Slider(
                        seeking ?: playback.positionMs.toFloat(),
                        { seeking = it },
                        valueRange = 0f..(duration?.toFloat()?.coerceAtLeast(1f) ?: 1f),
                        enabled =
                            duration != null &&
                                playback.status !in
                                    setOf(PlayerStatus.RESOLVING, PlayerStatus.FAILED),
                        onValueChangeFinished = {
                            seeking?.let { graph.player.seek(it.toLong()) }
                            seeking = null
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeLabel(playback.positionMs))
                        Text(duration?.let(::timeLabel) ?: "—:—")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        { scope.launch { graph.player.dispatch(QueueCommand.Previous) } },
                        enabled = queue.entries.isNotEmpty(),
                    ) {
                        Text("Назад")
                    }
                    Button(
                        {
                            if (playback.playWhenReady) graph.player.pause()
                            else graph.player.play()
                        },
                        enabled = queue.currentEntryId != null,
                    ) {
                        Text(if (playback.playWhenReady) "Пауза" else "Играть")
                    }
                    TextButton(
                        { scope.launch { graph.player.dispatch(QueueCommand.Next) } },
                        enabled = queue.entries.isNotEmpty(),
                    ) {
                        Text("Далее")
                    }
                }
                if (
                    playback.status == PlayerStatus.RESOLVING ||
                        playback.status == PlayerStatus.BUFFERING
                )
                    Text("Подготовка воспроизведения…", style = MaterialTheme.typography.bodySmall)
                playback.error?.let {
                    Text(
                        "Не удалось воспроизвести трек (${it.code}). Проверьте доступ к YouTube и наличие mpv в PATH или MOLPY_MPV.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun timeLabel(ms: Long): String =
    "${ms.coerceAtLeast(0)/60000}:${((ms.coerceAtLeast(0)/1000)%60).toString().padStart(2,'0')}"
