package app.musicplayer.ui.player

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.PublicPlaylistRef
import app.musicplayer.model.*
import app.musicplayer.queue.QueueOrder
import app.musicplayer.ui.components.*
import app.musicplayer.ui.theme.LocalAppTheme
import app.musicplayer.ui.theme.ThemeWindowAppearance
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerState,
    model: PlayerModel,
    artwork: ArtworkLoader,
    onOpenPublicPlaylist: ((PublicPlaylistRef) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val queue = presentQueue(state.queue, state.queueTracks)
    val latest by rememberUpdatedState(state)
    val rowHeight = with(LocalDensity.current) { 80.dp.toPx() }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ThemeWindowAppearance(LocalAppTheme.current)
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Очередь", style = MaterialTheme.typography.headlineMedium)
                    val loadedCount = queue.upcoming.size + if (queue.current != null) 1 else 0
                    Text(
                        if (queue.gap != null) "$loadedCount загружено · есть продолжение"
                        else "$loadedCount в очереди",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QueueShuffleButton(state.queue, model::toggleShuffle)
                IconButton(onClose) { Icon(AppIcons.Close, "Закрыть очередь") }
            }
            if (queue.shufflePending)
                Text(
                    "Подготавливаем перемешивание",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            LazyColumn(
                Modifier.weight(1f, fill = false).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                state.repositoryError?.let { error ->
                    item { InlineError(error, model::retryMetadata) }
                }
                state.commandError?.let { error -> item { InlineError(error) } }
                queue.sourceRecoveryError?.let { error -> item { InlineError(error) } }
                if (queue.gap == null)
                    queue.sourceError?.let { error ->
                        item(key = "source-error") {
                            Column {
                                InlineError(error)
                                if (queue.sourceCanRetry)
                                    TextButton(model::retryQueueSource) { Text("Повторить") }
                            }
                        }
                    }
                queue.current?.track?.let { track ->
                    item {
                        Text(
                            "Сейчас играет",
                            Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TrackRow(
                            track,
                            artwork,
                            { model.playPause() },
                            {},
                            selected = true,
                            trailing = {
                                IconButton(model::playPause) {
                                    Icon(
                                        if (state.snapshot.playWhenReady) AppIcons.Pause
                                        else AppIcons.Play,
                                        if (state.snapshot.playWhenReady) "Пауза" else "Продолжить",
                                    )
                                }
                            },
                        )
                        HorizontalDivider(
                            Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                    }
                }
                itemsIndexed(queue.upcoming, key = { _, item -> item.entryId.value }) {
                    globalIndex,
                    item ->
                    queue.gap
                        ?.takeIf { it.beforeIndex == globalIndex }
                        ?.let { gap ->
                            QueueSourceStatus(gap, model::retryQueueSource, onOpenPublicPlaylist)
                        }
                    val track = item.track
                    var horizontal by remember(item.entryId) { mutableFloatStateOf(0f) }
                    var vertical by remember(item.entryId) { mutableFloatStateOf(0f) }
                    Box(
                        Modifier.fillMaxWidth().pointerInput(item.entryId) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (horizontal < -100.dp.toPx()) model.removeEntry(item.entryId)
                                    horizontal = 0f
                                },
                                onDragCancel = { horizontal = 0f },
                            ) { change, amount ->
                                change.consume()
                                horizontal = (horizontal + amount).coerceAtMost(0f)
                            }
                        }
                    ) {
                        if (horizontal < 0)
                            Icon(
                                AppIcons.Trash,
                                "Удалить из очереди",
                                Modifier.align(Alignment.CenterEnd).padding(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        val modifier =
                            Modifier.offset { IntOffset(horizontal.roundToInt(), 0) }
                                .background(MaterialTheme.colorScheme.surface)
                                .semantics {
                                    customActions = buildList {
                                        if (
                                            canMoveQueueEntry(
                                                state.queue,
                                                item.entryId,
                                                globalIndex - 1,
                                            )
                                        )
                                            add(
                                                CustomAccessibilityAction("Выше") {
                                                    model.moveEntry(item.entryId, globalIndex - 1)
                                                    true
                                                }
                                            )
                                        if (
                                            canMoveQueueEntry(
                                                state.queue,
                                                item.entryId,
                                                globalIndex + 1,
                                            )
                                        )
                                            add(
                                                CustomAccessibilityAction("Ниже") {
                                                    model.moveEntry(item.entryId, globalIndex + 1)
                                                    true
                                                }
                                            )
                                        add(
                                            CustomAccessibilityAction("Удалить") {
                                                model.removeEntry(item.entryId)
                                                true
                                            }
                                        )
                                    }
                                }
                        if (track != null)
                            TrackRow(
                                track,
                                artwork,
                                { model.selectEntry(item.entryId) },
                                {},
                                modifier = modifier,
                                trailing = {
                                    Icon(
                                        AppIcons.Grip,
                                        "Переставить трек",
                                        Modifier.size(48.dp)
                                            .pointerInput(item.entryId, rowHeight) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragEnd = { vertical = 0f },
                                                    onDragCancel = { vertical = 0f },
                                                ) { change, amount ->
                                                    change.consume()
                                                    vertical += amount.y
                                                    val upcoming = QueueOrder.upcoming(latest.queue)
                                                    val index = upcoming.indexOfFirst {
                                                        it.id == item.entryId
                                                    }
                                                    if (index < 0)
                                                        return@detectDragGesturesAfterLongPress
                                                    val delta =
                                                        when {
                                                            vertical > rowHeight -> 1
                                                            vertical < -rowHeight -> -1
                                                            else -> 0
                                                        }
                                                    val target =
                                                        (index + delta).coerceIn(upcoming.indices)
                                                    if (delta != 0 && target != index) {
                                                        if (
                                                            canMoveQueueEntry(
                                                                latest.queue,
                                                                item.entryId,
                                                                target,
                                                            )
                                                        )
                                                            model.moveEntry(item.entryId, target)
                                                        vertical = 0f
                                                    }
                                                }
                                            }
                                            .padding(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        else
                            Row(
                                modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Запись недоступна", Modifier.weight(1f))
                                IconButton({ model.removeEntry(item.entryId) }) {
                                    Icon(AppIcons.Trash, "Удалить из очереди")
                                }
                            }
                    }
                }
                queue.gap
                    ?.takeIf { it.beforeIndex == queue.upcoming.size }
                    ?.let { gap ->
                        item(key = "source-gap") {
                            QueueSourceStatus(gap, model::retryQueueSource, onOpenPublicPlaylist)
                        }
                    }
                if (queue.upcoming.isEmpty() && queue.gap == null)
                    item {
                        Text(
                            "Дальше пока ничего нет",
                            Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            MiniPlayer(state, artwork, onClose, model::playPause, model::next)
        }
    }
}

@Composable
internal fun QueueShuffleButton(queue: QueueSnapshot, onClick: () -> Unit) {
    val pending = queue.source?.let { it.hasMore && it.shuffleRequested } == true
    if (pending) {
        IconToggleButton(
            checked = false,
            onCheckedChange = { onClick() },
            modifier =
                Modifier.size(48.dp).semantics {
                    contentDescription = "Отменить подготовку перемешивания"
                    stateDescription = "Подготавливаем перемешивание"
                },
        ) {
            Icon(
                AppIcons.Shuffle,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else ShuffleButton(queue.shuffleEnabled, onClick)
}

@Composable
private fun QueueSourceStatus(
    gap: QueueSourceGap,
    onRetry: () -> Unit,
    onOpenPublicPlaylist: ((PublicPlaylistRef) -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics {
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(
            queueSourceMessage(gap),
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        gap.error?.let { InlineError(it) }
        if (gap.canRetry) TextButton(onRetry) { Text("Повторить") }
        val ref = gap.reopen
        if (ref != null && onOpenPublicPlaylist != null)
            TextButton({ onOpenPublicPlaylist(ref) }) { Text("Открыть плейлист") }
    }
}
