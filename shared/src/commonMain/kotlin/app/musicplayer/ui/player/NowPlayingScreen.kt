package app.musicplayer.ui.player

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.components.*

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
) {
    val colors = MaterialTheme.colorScheme
    val track = state.currentTrack
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
        val cover = minOf(maxWidth * 0.72f, 320.dp, maxHeight * 0.42f)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
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
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val availableHeight = maxHeight
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = availableHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(cover + 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            queue.previous?.track?.let { previous ->
                                TrackArtwork(
                                    previous.artwork,
                                    artwork,
                                    Modifier.offset(x = -(cover * 0.2f))
                                        .size(cover * 0.8f)
                                        .alpha(0.30f)
                                        .clearAndSetSemantics {},
                                )
                            }
                            queue.next?.track?.let { next ->
                                TrackArtwork(
                                    next.artwork,
                                    artwork,
                                    Modifier.offset(x = cover * 0.2f)
                                        .size(cover * 0.8f)
                                        .alpha(0.30f)
                                        .clearAndSetSemantics {},
                                )
                            }
                            TrackArtwork(
                                track.artwork,
                                artwork,
                                Modifier.size(cover)
                                    .border(
                                        1.dp,
                                        colors.primary.copy(alpha = 0.25f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                    ),
                            )
                        }
                        Column(
                            Modifier.widthIn(max = 520.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        track.title,
                                        style = MaterialTheme.typography.headlineMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        track.artists.joinToString { it.name },
                                        Modifier.clickable(
                                            enabled = track.artists.firstOrNull()?.source != null
                                        ) {
                                            track.artists.firstOrNull()?.let(onArtist)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onFavorite, Modifier.size(48.dp)) {
                                    Icon(
                                        if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                                        if (favorite) "Убрать из избранного" else "В избранное",
                                        tint = if (favorite) colors.primary else colors.onSurface,
                                    )
                                }
                            }
                            track.album?.let { album ->
                                Text(
                                    album.name,
                                    Modifier.clickable(enabled = album.source != null) {
                                        onAlbum(album)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            var scrub by
                                remember(state.snapshot.current?.id) {
                                    mutableStateOf<Float?>(null)
                                }
                            val duration = state.snapshot.durationMs?.takeIf { it > 0 }
                            val position = scrub ?: state.snapshot.positionMs.toFloat()
                            Slider(
                                position.coerceIn(0f, (duration ?: 1).toFloat()),
                                { scrub = it },
                                Modifier.fillMaxWidth().semantics {
                                    contentDescription = "Позиция воспроизведения"
                                },
                                enabled = duration != null,
                                valueRange = 0f..(duration ?: 1).toFloat(),
                                onValueChangeFinished = {
                                    scrub?.let { model.seek(it.toLong()) }
                                    scrub = null
                                },
                                thumb = {
                                    Box(
                                        Modifier.size(12.dp).background(colors.primary, CircleShape)
                                    )
                                },
                                track = { slider ->
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .height(4.dp)
                                            .background(
                                                colors.onSurface.copy(alpha = 0.25f),
                                                CircleShape,
                                            )
                                    ) {
                                        val fraction =
                                            if (duration == null) 0f
                                            else
                                                (slider.value / duration.toFloat()).coerceIn(0f, 1f)
                                        Box(
                                            Modifier.fillMaxWidth(fraction)
                                                .fillMaxHeight()
                                                .background(colors.primary, CircleShape)
                                        )
                                    }
                                },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
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
                                    Icon(AppIcons.Previous, "Предыдущий трек", Modifier.size(32.dp))
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
                            state.snapshot.error?.let { InlineError(it, model::retry) }
                            state.commandError?.let { InlineError(it, model::retry) }
                        }
                    }
                }
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
                        .clickable(onClick = onQueue)
                        .semantics { contentDescription = "Открыть очередь" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Queue, null, Modifier.size(28.dp).offset(y = (-4).dp))
                    Icon(AppIcons.Queue, null, Modifier.size(28.dp).offset(y = 4.dp))
                }
                val available = (state.lyrics as? LyricsLookup.Available)?.lyrics
                if (available != null || state.loadingLyrics || state.lyricsError != null) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 12.dp)
                            .clickable(enabled = available != null, onClick = onLyrics)
                    ) {
                        HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))
                        Text(
                            "Текст:",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        when {
                            available != null ->
                                Text(
                                    if (available.kind == LyricsKind.LINE_SYNCED)
                                        LyricsPosition.activeLine(
                                                available,
                                                state.snapshot.positionMs,
                                            )
                                            ?.let { available.lines[it].text }
                                            .orEmpty()
                                    else "Открыть текст",
                                    Modifier.heightIn(min = 24.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            state.loadingLyrics ->
                                Text(
                                    "Загружаем текст…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            else ->
                                TextButton(
                                    model::loadLyrics,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text("Загрузить текст ещё раз")
                                }
                        }
                    }
                }
            }
        }
    }
}
