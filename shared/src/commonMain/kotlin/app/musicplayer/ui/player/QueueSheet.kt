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
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerState,
    model: PlayerModel,
    artwork: ArtworkLoader,
    onClose: () -> Unit,
) {
    val queue = presentQueue(state.queue, state.queueTracks)
    val latest by rememberUpdatedState(state)
    val rowHeight = with(LocalDensity.current) { 80.dp.toPx() }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Очередь", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${queue.upcoming.size+if(queue.current != null) 1 else 0} в очереди",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClose) { Icon(AppIcons.Close, "Закрыть очередь") }
            }
            LazyColumn(
                Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                state.repositoryError?.let { error ->
                    item { InlineError(error, model::retryMetadata) }
                }
                state.commandError?.let { error -> item { InlineError(error) } }
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
                items(queue.upcoming, key = { it.entryId.value }) { item ->
                    val track = item.track
                    var horizontal by remember(item.entryId) { mutableFloatStateOf(0f) }
                    var vertical by remember(item.entryId) { mutableFloatStateOf(0f) }
                    val globalIndex = state.queue.entries.indexOfFirst { it.id == item.entryId }
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
                                    customActions =
                                        listOf(
                                            CustomAccessibilityAction("Выше") {
                                                if (
                                                    globalIndex >
                                                        state.queue.entries.indexOfFirst {
                                                            it.id == state.queue.currentEntryId
                                                        } + 1
                                                )
                                                    model.moveEntry(item.entryId, globalIndex - 1)
                                                true
                                            },
                                            CustomAccessibilityAction("Ниже") {
                                                if (globalIndex < state.queue.entries.lastIndex)
                                                    model.moveEntry(item.entryId, globalIndex + 1)
                                                true
                                            },
                                            CustomAccessibilityAction("Удалить") {
                                                model.removeEntry(item.entryId)
                                                true
                                            },
                                        )
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
                                                    val index =
                                                        latest.queue.entries.indexOfFirst {
                                                            it.id == item.entryId
                                                        }
                                                    val delta =
                                                        when {
                                                            vertical > rowHeight -> 1
                                                            vertical < -rowHeight -> -1
                                                            else -> 0
                                                        }
                                                    val firstUpcoming =
                                                        (latest.queue.entries.indexOfFirst {
                                                                it.id == latest.queue.currentEntryId
                                                            } + 1)
                                                            .coerceAtLeast(0)
                                                    if (
                                                        index < firstUpcoming ||
                                                            index < 0 ||
                                                            firstUpcoming >
                                                                latest.queue.entries.lastIndex
                                                    )
                                                        return@detectDragGesturesAfterLongPress
                                                    val target =
                                                        (index + delta).coerceIn(
                                                            firstUpcoming,
                                                            latest.queue.entries.lastIndex,
                                                        )
                                                    if (delta != 0 && target != index) {
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
                if (queue.upcoming.isEmpty())
                    item {
                        Text(
                            "Дальше пока ничего нет",
                            Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
}
