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
fun TechnicalApp(state: TechnicalState, model: TechnicalScreenModel, artwork: ArtworkLoader) {
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
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text("Название или исполнитель") }, singleLine = true)
                    Button(onClick = { scope.launch { model.search(query) } }) { Text("Найти") }
                    if (state.loadingSearch) Text("Поиск…")
                    state.searchError?.let { Text("Поиск: ${errorText(it.code)}") }
                    if (!state.loadingSearch && state.query.isNotBlank() && state.results.isEmpty() && state.searchError == null) Text("Ничего не найдено")
                }
                if (selected != null) item {
                    Text("Выбрано", style = MaterialTheme.typography.titleMedium)
                    AsyncImage(model = selected.artwork.remoteUrl, imageLoader = artwork.imageLoader,
                        contentDescription = "Обложка: ${selected.title}", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp))
                    TrackMetadata(selected)
                    Text("Состояние: ${statusText(state.playback.status)}")
                    state.playback.error?.let { Text("Воспроизведение: ${errorText(it.code)}") }
                    Text("${timeText(state.playback.positionMs)} / ${duration?.let(::timeText) ?: "—"}")
                    Slider(value = scrub ?: (duration?.let { state.playback.positionMs.toFloat() / it } ?: 0f).coerceIn(0f, 1f),
                        onValueChange = { scrub = it }, enabled = duration != null,
                        onValueChangeFinished = { scrub?.let { value -> duration?.let { model.seek((value * it).toLong()) } }; scrub = null })
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
                            Text("Текст: ${errorText(lookup.error.code)}")
                            Button(onClick = model::loadLyrics) { Text("Повторить загрузку текста") }
                        }
                        null -> Unit
                    }
                }
                item { Text("Результаты", style = MaterialTheme.typography.titleMedium) }
                items(state.results, key = { it.id.value }) { track ->
                    OutlinedButton(onClick = { model.select(track) }, modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsyncImage(model = track.artwork.remoteUrl, imageLoader = artwork.imageLoader,
                                contentDescription = "Обложка: ${track.title}", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp))
                            Column(Modifier.weight(1f)) { TrackMetadata(track) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMetadata(track: Track) {
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
    track.candidates.firstOrNull()?.let { candidate ->
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

private fun errorText(code: ErrorCode): String = when (code) {
    ErrorCode.NETWORK -> "Нет соединения или источник не отвечает"
    ErrorCode.SOURCE_UNAVAILABLE -> "Источник недоступен"
    ErrorCode.REGION_RESTRICTED -> "Запись недоступна в этом регионе"
    ErrorCode.LOGIN_REQUIRED -> "Источник требует входа"
    ErrorCode.RATE_LIMIT -> "Слишком много запросов, повторите позже"
    ErrorCode.PROVIDER_CHANGED -> "Ответ источника не поддерживается"
    ErrorCode.UNSUPPORTED_TRANSPORT -> "Источник не поддерживает доступный способ воспроизведения"
    ErrorCode.URL_REJECTED -> "Источник отклонил воспроизведение"
    ErrorCode.PROXY_AUTH -> "Ошибка авторизации прокси"
    ErrorCode.PROXY_UNSUPPORTED -> "Этот прокси не поддерживается"
    ErrorCode.INVALID_CONFIG -> "Неполные данные для запроса"
    ErrorCode.INCOMPLETE_MEDIA -> "Запись получена не полностью"
    ErrorCode.INCOMPATIBLE_RESOURCE -> "Ресурс несовместим"
    ErrorCode.STORAGE_FULL -> "Недостаточно места"
    ErrorCode.DATABASE -> "Ошибка локальных данных"
    ErrorCode.UNKNOWN -> "Не удалось выполнить запрос"
}
