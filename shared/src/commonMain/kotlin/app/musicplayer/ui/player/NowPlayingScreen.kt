package app.musicplayer.ui.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.present
import app.musicplayer.ui.theme.ArtworkAmbientBackground
import app.musicplayer.ui.theme.LocalArtworkColors

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
    onPlaylist: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    downloadLabel: String = "Скачать",
    downloadEnabled: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val track = state.currentTrack
    PlayerSheet(visible, gesturesEnabled, onClose) { dragHandle ->
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().testTag("now-playing")) {
            val compact = maxHeight < 700.dp || LocalDensity.current.fontScale > 1.1f
            val horizontalFooter = maxWidth > maxHeight
            val spaciousFooter = maxHeight >= 820.dp && LocalDensity.current.fontScale <= 1.1f
            ArtworkAmbientBackground(LocalArtworkColors.current, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize()) {
                PlayerHeader(
                    track?.album,
                    onClose,
                    { track?.let(onMenu) },
                    onAlbum,
                    menuEnabled = track != null,
                    modifier = dragHandle,
                )
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
                        entryKey = state.snapshot.current?.id ?: track.id,
                        artwork = {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val topGap = if (compact) 0.dp else 24.dp
                                val sideGap = if (maxWidth < 200.dp) 16.dp else 64.dp
                                val cover =
                                    minOf(
                                        (maxWidth - sideGap).coerceAtLeast(0.dp),
                                        360.dp,
                                        (maxHeight - topGap - 16.dp).coerceAtLeast(0.dp),
                                    )
                                Box(Modifier.padding(top = topGap)) {
                                    TrackArtworkStage(
                                        visuals,
                                        state.trackMotion,
                                        artwork,
                                        cover,
                                        dragHandle,
                                    )
                                }
                            }
                        },
                        metadata = {
                            Column(
                                Modifier.widthIn(max = 520.dp)
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 24.dp,
                                        vertical = 4.dp,
                                    )
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
                                                style =
                                                    MaterialTheme.typography.headlineMedium.copy(
                                                        fontSize = 26.sp,
                                                        lineHeight = 30.sp,
                                                    ),
                                                minLines = 1,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val artistCredits =
                                                trackPresentation(shown.track).artists
                                            Text(
                                                artistCredits
                                                    .joinToString(" · ") { it.name }
                                                    .ifBlank { "Исполнитель не указан" },
                                                Modifier.fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .clickable(
                                                        enabled =
                                                            active && artistCredits.isNotEmpty()
                                                    ) {
                                                        if (onArtists != null)
                                                            onArtists(shown.track)
                                                        else
                                                            shown.track.artists
                                                                .firstOrNull()
                                                                ?.let(onArtist)
                                                    }
                                                    .wrapContentHeight(),
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onFavorite,
                                        Modifier.size(48.dp).align(Alignment.Top),
                                    ) {
                                        Icon(
                                            if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                                            if (favorite) "Убрать из избранного" else "В избранное",
                                            tint =
                                                if (favorite) colors.primary else colors.onSurface,
                                        )
                                    }
                                }
                                if (
                                    state.snapshot.error != null ||
                                        state.commandError != null ||
                                        queue.gap != null ||
                                        queue.sourceError != null ||
                                        queue.sourceRecoveryError != null ||
                                        queue.shufflePending ||
                                        state.snapshot.status in
                                            setOf(PlayerStatus.RESOLVING, PlayerStatus.BUFFERING)
                                )
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .height(48.dp * LocalDensity.current.fontScale),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        val error = state.snapshot.error ?: state.commandError
                                        val gap = queue.gap
                                        val retrySource =
                                            queue.sourceCanRetry &&
                                                (error == queue.sourceError ||
                                                    (error?.code == ErrorCode.DATABASE &&
                                                        queue.sourceError?.code ==
                                                            ErrorCode.DATABASE))
                                        when {
                                            error != null ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        present(error).message,
                                                        Modifier.weight(1f),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (error.retryable || retrySource)
                                                        TextButton(
                                                            if (retrySource) model::retryQueueSource
                                                            else model::retry
                                                        ) {
                                                            Text("Повторить")
                                                        }
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
                                            gap != null &&
                                                (gap.waiting ||
                                                    gap.error != null ||
                                                    gap.phase != QueueSourcePhase.LOADING) -> {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        queueSourceMessage(gap),
                                                        Modifier.weight(1f).semantics {
                                                            liveRegion = LiveRegionMode.Polite
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (gap.canRetry)
                                                        TextButton(model::retryQueueSource) {
                                                            Text("Повторить")
                                                        }
                                                }
                                            }
                                            queue.sourceError != null ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        present(queue.sourceError).message,
                                                        Modifier.weight(1f).semantics {
                                                            liveRegion = LiveRegionMode.Polite
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (queue.sourceCanRetry)
                                                        TextButton(model::retryQueueSource) {
                                                            Text("Повторить")
                                                        }
                                                }
                                            queue.sourceRecoveryError != null ->
                                                Text(
                                                    present(queue.sourceRecoveryError).message,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            queue.shufflePending ->
                                                Text(
                                                    "Подготавливаем перемешивание",
                                                    Modifier.semantics {
                                                        liveRegion = LiveRegionMode.Polite
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant,
                                                )
                                        }
                                    }
                            }
                        },
                        transport = {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                key(state.snapshot.current?.id) {
                                    PlaybackProgress(
                                        state.snapshot.positionMs,
                                        state.effectiveDurationMs,
                                        state.snapshot.bufferedPositionMs,
                                        model::seek,
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                                BoxWithConstraints(
                                    Modifier.fillMaxWidth()
                                        .height(64.dp)
                                        .testTag("player-transport")
                                ) {
                                    val navigationSize = if (maxWidth < 280.dp) 48.dp else 56.dp
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        QueueShuffleButton(state.queue, model::toggleShuffle)
                                        Row(
                                            Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            IconButton(
                                                model::previous,
                                                Modifier.size(navigationSize),
                                                enabled =
                                                    queue.previous != null ||
                                                        state.snapshot.positionMs > 0,
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
                                            IconButton(
                                                model::playPause,
                                                Modifier.size(64.dp),
                                            ) {
                                                Icon(
                                                    if (active) AppIcons.Pause else AppIcons.Play,
                                                    if (active) "Пауза" else "Воспроизвести",
                                                    Modifier.size(64.dp),
                                                )
                                            }
                                            IconButton(
                                                model::next,
                                                Modifier.size(navigationSize),
                                                enabled = queue.canRequestNext,
                                            ) {
                                                Icon(
                                                    AppIcons.Next,
                                                    "Следующий трек",
                                                    Modifier.size(32.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(48.dp))
                                    }
                                }

                                PlayerQuickActions(
                                    onLyrics,
                                    onPlaylist,
                                    onDownload,
                                    downloadLabel,
                                    downloadEnabled,
                                )
                            }
                        },
                    )
                    PlayerLyricsFooter(
                        state,
                        onQueue,
                        onLyrics,
                        model::loadLyrics,
                        spacious = spaciousFooter,
                        horizontal = horizontalFooter,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerQuickActions(
    onLyrics: () -> Unit,
    onPlaylist: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    downloadLabel: String,
    downloadEnabled: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelMedium
        val density = LocalDensity.current
        val required =
            listOf("Текст", "В плейлист", downloadLabel).map { label ->
                with(density) {
                    measurer.measure(AnnotatedString(label), style = style).size.width.toDp()
                } + 40.dp
            }
        val weights = listOf(1f, 1.35f, 1.15f)
        val available = maxWidth - 16.dp
        val scrolling = required.indices.any { required[it] > available * (weights[it] / 3.5f) }
        val scroll = rememberScrollState()
        Row(
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .then(if (scrolling) Modifier.horizontalScroll(scroll) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerQuickAction(
                "Текст",
                AppIcons.Queue,
                onLyrics,
                if (scrolling) Modifier else Modifier.weight(1f),
            )
            PlayerQuickAction(
                "В плейлист",
                AppIcons.PlaylistAdd,
                { onPlaylist?.invoke() },
                if (scrolling) Modifier else Modifier.weight(1.35f),
                onPlaylist != null,
            )
            PlayerQuickAction(
                downloadLabel,
                AppIcons.Download,
                { onDownload?.invoke() },
                if (scrolling) Modifier else Modifier.weight(1.15f),
                downloadEnabled && onDownload != null,
            )
        }
    }
}

@Composable
private fun PlayerQuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
