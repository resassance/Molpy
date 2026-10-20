package app.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Deliberately plain technical surface; final navigation and visual design belong to later work. */
@Composable
fun TechnicalApp(state: TechnicalState, model: TechnicalScreenModel, artwork: ArtworkLoader, onManageStorage: () -> Unit) {
    var surface by remember { mutableStateOf("Поиск") }
    var query by remember { mutableStateOf(state.query) }
    var showLyrics by remember(state.selected?.id) { mutableStateOf(false) }
    var scrub by remember(state.selected?.id) { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val selected = state.selected
    val duration = state.playback.durationMs?.takeIf { it > 0 }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Molpy · технический экран", style = MaterialTheme.typography.titleLarge) }
                item {
                    Row { listOf("Поиск", "Очередь", "Медиатека", "Загрузки", "Сеть").forEach { name -> TextButton(onClick = { surface = name }) { Text(name) } } }
                    state.commandError?.let { error -> ErrorMessage(error) { action ->
                        performErrorAction(action, model::retryCommand, model::next, { surface = "Сеть" }, onManageStorage)
                    } }
                    state.repositoryError?.let { error -> ErrorMessage(error) { action ->
                        performErrorAction(action, model::retryRepository, model::next, { surface = "Сеть" }, onManageStorage)
                    } }
                    state.playback.error?.let { error -> ErrorMessage(error) { action -> when (action) {
                        ErrorAction.RETRY -> model.retry()
                        ErrorAction.NEXT -> model.next()
                        ErrorAction.EDIT_NETWORK -> surface = "Сеть"
                        ErrorAction.MANAGE_STORAGE -> onManageStorage()
                        ErrorAction.NONE -> Unit
                    } } }
                }
                if (surface == "Загрузки") item { DownloadsScreen(state.downloads, state.downloadFailures, state.storageUsage,
                    model::pauseDownload, model::resumeDownload, model::cancelDownload, model::deleteDownload, model::removeAllDownloads,
                    { scope.launch { model.refreshStorage() } }, onManageStorage) }
                if (surface == "Очередь") item { QueueScreen(state.queue, state.queueTracks, model::selectQueue, model::removeQueue, model::next, model::previous) }
                if (surface == "Медиатека") item { LibraryScreen(state.favorites, state.recent, model::select, model::addToQueue, model::setFavorite) }
                if (surface == "Сеть") item { NetworkSettingsScreen(state.networkDraft, state.networkHasCredential, state.networkApplying,
                    state.networkMessage, state.networkError, model::editNetwork, { password -> scope.launch { model.applyNetwork(password) } }) }
                if (surface == "Поиск") item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text("Название или исполнитель") }, singleLine = true)
                    Button(onClick = { scope.launch { model.search(query) } }) { Text("Найти") }
                    if (state.loadingSearch) Text("Поиск…")
                    state.searchError?.let { error -> ErrorMessage(error) { action -> when (action) {
                        ErrorAction.RETRY -> scope.launch { model.search(query) }
                        ErrorAction.EDIT_NETWORK -> surface = "Сеть"
                        ErrorAction.MANAGE_STORAGE -> onManageStorage()
                        else -> Unit
                    } } }
                    if (!state.loadingSearch && state.query.isNotBlank() && state.results.isEmpty() && state.searchError == null) Text("Ничего не найдено")
                }
                if (selected != null) item {
                    Text("Выбрано", style = MaterialTheme.typography.titleMedium)
                    AsyncImage(model = selected.artwork.localKey ?: selected.artwork.remoteUrl, imageLoader = artwork.imageLoader,
                        contentDescription = "Обложка: ${selected.title}", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp))
                    TrackMetadata(selected, state.playback.candidateId)
                    TextButton(onClick = { model.download(selected) }) { Text("Скачать для офлайн") }
                    Text("Состояние: ${statusText(state.playback.status)}")
                    Text("${timeText(state.playback.positionMs)} / ${duration?.let(::timeText) ?: "—"}")
                    Slider(value = scrub ?: (duration?.let { state.playback.positionMs.toFloat() / it } ?: 0f).coerceIn(0f, 1f),
                        onValueChange = { scrub = it }, enabled = duration != null,
                        onValueChangeFinished = { scrub?.let { value -> duration?.let { model.seek((value * it).toLong()) } }; scrub = null })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::previous) { Text("Предыдущая") }
                        TextButton(onClick = model::next) { Text("Следующая") }
                    }
                    TextButton(onClick = { model.setFavorite(selected, selected.id !in state.favoriteIds) }) {
                        Text(if (selected.id in state.favoriteIds) "Убрать из избранного" else "В избранное")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::playPause) {
                            Text(if (state.playback.status == PlayerStatus.FAILED) "Повторить"
                                else if (state.playback.playWhenReady) "Пауза" else "Слушать")
                        }
                        Button(onClick = {
                            showLyrics = !showLyrics
                            if (showLyrics && state.lyrics == null) model.loadLyrics()
                        }) { Text(if (showLyrics) "Скрыть текст" else "Текст песни") }
                    }
                }
                if (showLyrics) item {
                    if (state.loadingLyrics) Text("Загрузка текста…")
                    when (val lookup = state.lyrics) {
                        is LyricsLookup.Available -> {
                            if (lookup.lyrics.kind == LyricsKind.LINE_SYNCED) {
                                lookup.lyrics.lines.forEachIndexed { index, line ->
                                    val end = line.endMs ?: lookup.lyrics.lines.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
                                    val active = state.playback.positionMs >= line.startMs && state.playback.positionMs < end
                                    Text(line.text, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                                }
                            } else Text(lookup.lyrics.plainText.orEmpty())
                        }
                        is LyricsLookup.Instrumental -> Text("Инструментальная запись")
                        LyricsLookup.Unavailable -> Text("Текст не найден")
                        is LyricsLookup.Failed -> {
                            ErrorMessage(lookup.error) { action ->
                                performErrorAction(action, model::loadLyrics, model::next, { surface = "Сеть" }, onManageStorage)
                            }
                        }
                        null -> Unit
                    }
                }
                if (surface == "Поиск") item { Text("Результаты", style = MaterialTheme.typography.titleMedium) }
                if (surface == "Поиск") items(state.results, key = { it.id.value }) { track ->
                    Column {
                    OutlinedButton(onClick = { model.select(track) }, modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsyncImage(model = track.artwork.localKey ?: track.artwork.remoteUrl, imageLoader = artwork.imageLoader,
                                contentDescription = "Обложка: ${track.title}", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp))
                            Column(Modifier.weight(1f)) { TrackMetadata(track) }
                        }
                    }
                    Row {
                        TextButton(onClick = { model.addToQueue(track) }) { Text("В очередь") }
                        TextButton(onClick = { model.setFavorite(track, track.id !in state.favoriteIds) }) {
                            Text(if (track.id in state.favoriteIds) "Убрать из избранного" else "В избранное")
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorMessage(error: AppError, onAction: (ErrorAction) -> Unit) {
    val presentation = present(error)
    Text(presentation.message)
    if (presentation.primaryAction != ErrorAction.NONE) TextButton(onClick = { onAction(presentation.primaryAction) }) {
        Text(when (presentation.primaryAction) {
            ErrorAction.RETRY -> "Повторить"
            ErrorAction.NEXT -> "Следующая запись"
            ErrorAction.EDIT_NETWORK -> "Настройки сети"
            ErrorAction.MANAGE_STORAGE -> "Управление хранилищем"
            ErrorAction.NONE -> ""
        })
    }
}

@Composable
private fun TrackMetadata(track: Track, candidateId: CandidateId? = null) {
    Text(track.title)
    Text(track.artists.joinToString(", ") { it.name }.ifBlank { "Исполнитель неизвестен" })
    track.album?.name?.let { Text(it) }
    track.durationMs?.let { Text("Длительность записи: ${timeText(it)}") }
    Text(when (track.artwork.kind) {
        ArtworkKind.RELEASE -> "Обложка релиза"
        ArtworkKind.VIDEO -> "Изображение видео"
        ArtworkKind.PLACEHOLDER -> "Обложка недоступна"
    })
    if (track.provenance.tentative) Text("Неполные данные источника")
    (candidateId?.let { id -> track.candidates.find { it.id == id } } ?: track.candidates.firstOrNull())?.let { candidate ->
        Text("Источник: ${candidate.source.provider}/${candidate.source.kind}/${candidate.source.id}")
        Text("Версия: ${candidate.version.ifBlank { "не указана источником" }}")
    }
}

private fun timeText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun statusText(status: PlayerStatus): String = when (status) {
    PlayerStatus.IDLE -> "Ожидание"
    PlayerStatus.RESOLVING -> "Подготовка источника"
    PlayerStatus.BUFFERING -> "Буферизация"
    PlayerStatus.PLAYING -> "Играет"
    PlayerStatus.PAUSED -> "Пауза"
    PlayerStatus.ENDED -> "Завершено"
    PlayerStatus.FAILED -> "Ошибка"
}

