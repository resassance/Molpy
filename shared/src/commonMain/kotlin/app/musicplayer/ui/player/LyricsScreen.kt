package app.musicplayer.ui.player

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.theme.ArtworkAmbientBackground
import app.musicplayer.ui.theme.LocalArtworkColors

@Composable
fun LyricsScreen(state: PlayerState, onClose: () -> Unit, onRetry: () -> Unit) {
    LyricsContent(state, onClose, onRetry, null, null, null, null)
}

@Composable
fun LyricsScreen(
    state: PlayerState,
    artwork: ArtworkLoader,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    LyricsContent(state, onClose, onRetry, artwork, onPlayPause, onNext, onSeek)
}

@Composable
private fun LyricsContent(
    state: PlayerState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    artwork: ArtworkLoader?,
    onPlayPause: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onSeek: ((Long) -> Unit)?,
) {
    val lyrics = (state.lyrics as? LyricsLookup.Available)?.lyrics
    val active = lyrics?.let { LyricsPosition.activeLine(it, state.snapshot.positionMs) }
    val list = rememberLazyListState()
    var following by
        remember(state.snapshot.current?.id, state.snapshot.candidateId) { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress && !programmatic) following = false
    }
    LaunchedEffect(state.snapshot.current?.id, state.snapshot.candidateId, active, following) {
        if (following && active != null) {
            programmatic = true
            try {
                list.animateScrollToItem(active)
            } finally {
                programmatic = false
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showHeaderTrack = maxHeight >= 500.dp
            ArtworkAmbientBackground(LocalArtworkColors.current, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                CompactHeader("Текст", onBack = onClose)
                state.currentTrack
                    ?.takeIf { showHeaderTrack }
                    ?.let { track ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (artwork != null) {
                                TrackArtwork(track.artwork, artwork, Modifier.size(80.dp))
                                Spacer(Modifier.width(16.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    trackPresentation(track).artists.joinToString { it.name },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                track.album?.let {
                                    Text(
                                        it.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                when {
                    state.loadingLyrics ->
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    lyrics != null && lyrics.kind == LyricsKind.LINE_SYNCED ->
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            state = list,
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 24.dp,
                                    bottom = 24.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(lyrics.lines) { index, line ->
                                Text(
                                    line.text,
                                    fontSize = 24.sp,
                                    lineHeight = 30.sp,
                                    fontWeight =
                                        if (index == active) FontWeight.Bold else FontWeight.Medium,
                                    color =
                                        if (index == active) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    lyrics != null ->
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp)) {
                            item {
                                Text(
                                    lyrics.plainText.orEmpty(),
                                    fontSize = 20.sp,
                                    lineHeight = 32.sp,
                                )
                            }
                        }
                    else ->
                        Column(Modifier.weight(1f).padding(24.dp)) {
                            Text("Текст пока недоступен")
                            if (state.lyricsError != null) TextButton(onRetry) { Text("Повторить") }
                        }
                }
                if (!following && active != null)
                    TextButton({ following = true }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text("К текущей строке")
                    }
                if (artwork != null && onPlayPause != null && onNext != null && onSeek != null) {
                    LyricsTransport(state, artwork, onPlayPause, onNext, onSeek)
                }
            }
        }
    }
}

@Composable
private fun LyricsTransport(
    state: PlayerState,
    artwork: ArtworkLoader,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val track = state.currentTrack ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track.artwork, artwork, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        trackPresentation(track).artists.joinToString { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val active =
                    state.snapshot.status == PlayerStatus.PLAYING || state.snapshot.playWhenReady
                IconButton(onPlayPause, Modifier.size(48.dp)) {
                    Icon(
                        if (active) AppIcons.Pause else AppIcons.Play,
                        if (active) "Пауза" else "Воспроизвести",
                    )
                }
                IconButton(
                    onNext,
                    Modifier.size(48.dp),
                    enabled = presentQueue(state.queue, state.queueTracks).canRequestNext,
                ) {
                    Icon(AppIcons.Next, "Следующий трек")
                }
            }
            key(state.snapshot.current?.id) {
                PlaybackProgress(
                    state.snapshot.positionMs,
                    state.effectiveDurationMs,
                    state.snapshot.bufferedPositionMs,
                    onSeek,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
