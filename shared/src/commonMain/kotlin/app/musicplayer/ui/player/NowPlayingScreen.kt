package app.musicplayer.ui.player

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.present

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: PlayerState,
    model: PlayerModel,
    artwork: ArtworkLoader,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onLyrics: () -> Unit,
    onMenu: (Track) -> Unit,
    onArtist: (ArtistRef) -> Unit,
    onAlbum: (AlbumRef) -> Unit,
    visible: Boolean = true,
    gesturesEnabled: Boolean = true,
    onArtists: ((Track) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val track = state.currentTrack
    PlayerSheet(visible, gesturesEnabled, onClose) { dragHandle ->
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.primary.copy(alpha = 0.19f), colors.background)
                    )
                )
                .safeDrawingPadding()
                .testTag("now-playing")
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    dragHandle.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClose) { Icon(AppIcons.Down, "Свернуть плеер") }
                    Text(
                        "СЕЙЧАС ИГРАЕТ",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        letterSpacing = 2.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton({ track?.let(onMenu) }, enabled = track != null) {
                        Icon(AppIcons.More, "Действия с треком")
                    }
                }
                if (track == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Выберите трек")
                    }
                } else {
                    val queue = presentQueue(state.queue, state.queueTracks)
                    val visuals =
                        TrackVisuals(
                            state.snapshot.current?.id,
                            track,
                            queue.previous?.track?.artwork,
                            queue.next?.track?.artwork,
                        )
                    PlayerBody(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        artwork = {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val cover =
                                    minOf(
                                        maxWidth * 0.72f,
                                        320.dp,
                                        (maxHeight - 16.dp).coerceAtLeast(0.dp),
                                    )
                                TrackArtworkStage(
                                    visuals,
                                    state.trackMotion,
                                    artwork,
                                    cover,
                                    dragHandle,
                                )
                            }
                        },
                        controls = {
                            Column(
                                Modifier.widthIn(max = 520.dp)
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TrackChangeContent(
                                        visuals,
                                        state.trackMotion,
                                        Modifier.weight(1f),
                                    ) { shown, active ->
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                shown.track.title,
                                                style = MaterialTheme.typography.headlineMedium,
                                                minLines = 2,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val artistCredits =
                                                trackPresentation(shown.track).artists
                                            Text(
                                                artistCredits.joinToString { it.name },
                                                Modifier.clickable(
                                                    enabled = active && artistCredits.isNotEmpty()
                                                ) {
                                                    if (onArtists != null) onArtists(shown.track)
                                                    else
                                                        shown.track.artists
                                                            .firstOrNull()
                                                            ?.let(onArtist)
                                                },
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    IconButton(onFavorite, Modifier.size(48.dp)) {
                                        Icon(
                                            if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                                            if (favorite) "Убрать из избранного" else "В избранное",
                                            tint =
                                                if (favorite) colors.primary else colors.onSurface,
                                        )
                                    }
                                }
                                TrackChangeContent(
                                    visuals,
                                    state.trackMotion,
                                    Modifier.fillMaxWidth(),
                                ) { shown, active ->
                                    val album = shown.track.album
                                    Text(
                                        album?.name.orEmpty(),
                                        Modifier.clickable(
                                            enabled = active && album?.source != null
                                        ) {
                                            album?.let(onAlbum)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                        minLines = 1,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                key(state.snapshot.current?.id) {
                                    PlaybackProgress(
                                        state.snapshot.positionMs,
                                        state.effectiveDurationMs,
                                        state.snapshot.bufferedPositionMs,
                                        model::seek,
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        model::previous,
                                        Modifier.size(56.dp),
                                        enabled =
                                            queue.previous != null || state.snapshot.positionMs > 0,
                                    ) {
                                        Icon(
                                            AppIcons.Previous,
                                            "Предыдущий трек",
                                            Modifier.size(32.dp),
                                        )
                                    }
                                    val active =
                                        state.snapshot.status == PlayerStatus.PLAYING ||
                                            state.snapshot.playWhenReady
                                    FilledIconButton(
                                        model::playPause,
                                        Modifier.size(72.dp),
                                        shape = CircleShape,
                                    ) {
                                        Icon(
                                            if (active) AppIcons.Pause else AppIcons.Play,
                                            if (active) "Пауза" else "Воспроизвести",
                                            Modifier.size(36.dp),
                                        )
                                    }
                                    IconButton(
                                        model::next,
                                        Modifier.size(56.dp),
                                        enabled = queue.next != null,
                                    ) {
                                        Icon(AppIcons.Next, "Следующий трек", Modifier.size(32.dp))
                                    }
                                }
                                Box(
                                    Modifier.fillMaxWidth()
                                        .height(48.dp * LocalDensity.current.fontScale),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    val error = state.snapshot.error ?: state.commandError
                                    when {
                                        error != null ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    present(error).message,
                                                    Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (error.retryable)
                                                    TextButton(model::retry) { Text("Повторить") }
                                            }
                                        state.snapshot.status == PlayerStatus.RESOLVING ->
                                            Text(
                                                "Подготавливаем трек…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        state.snapshot.status == PlayerStatus.BUFFERING ->
                                            Text(
                                                "Загружаем аудио…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                    }
                                }
                            }
                        },
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .height(48.dp)
                            .pointerInput(onQueue) {
                                var distance = 0f
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (distance < -24.dp.toPx()) onQueue()
                                        distance = 0f
                                    },
                                    onDragCancel = { distance = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    distance += amount
                                }
                            }
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onQueue,
                            )
                            .semantics { contentDescription = "Открыть очередь" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Queue, null, Modifier.size(28.dp).offset(y = (-4).dp))
                        Icon(AppIcons.Queue, null, Modifier.size(28.dp).offset(y = 4.dp))
                    }
                    val available = (state.lyrics as? LyricsLookup.Available)?.lyrics
                    val lyricsFailed =
                        state.lyricsError != null || state.lyrics is LyricsLookup.Failed
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 12.dp)
                            .clickable(enabled = !state.loadingLyrics) {
                                if (available != null) onLyrics() else model.loadLyrics()
                            }
                    ) {
                        HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))
                        Text(
                            "Текст:",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        val line =
                            when {
                                available?.kind == LyricsKind.LINE_SYNCED ->
                                    LyricsPosition.activeLine(available, state.snapshot.positionMs)
                                        ?.let { available.lines[it].text }
                                        ?.takeIf { it.isNotBlank() } ?: "Открыть текст"
                                available != null -> "Открыть текст"
                                state.loadingLyrics -> "Загружаем текст…"
                                lyricsFailed -> "Не удалось загрузить. Повторить"
                                state.lyrics != null -> "Текст недоступен"
                                else -> "Загрузить текст"
                            }
                        Text(
                            line,
                            Modifier.heightIn(min = 24.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (available != null) colors.onSurface
                                else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
