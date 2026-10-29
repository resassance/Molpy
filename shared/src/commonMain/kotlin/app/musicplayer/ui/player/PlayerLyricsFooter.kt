package app.musicplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.model.*
import app.musicplayer.ui.components.AppIcons

@Composable
internal fun PlayerLyricsFooter(
    state: PlayerState,
    onQueue: () -> Unit,
    onLyrics: () -> Unit,
    onRetryLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    spacious: Boolean = false,
    horizontal: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val available = (state.lyrics as? LyricsLookup.Available)?.lyrics
    val failed = state.lyricsError != null || state.lyrics is LyricsLookup.Failed
    val line =
        when {
            available?.kind == LyricsKind.LINE_SYNCED ->
                LyricsPosition.activeLine(available, state.snapshot.positionMs)
                    ?.let { available.lines[it].text }
                    ?.takeIf { it.isNotBlank() } ?: "Открыть текст"
            available?.kind == LyricsKind.PLAIN ->
                available.plainText?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    ?: available.lines.firstOrNull { it.text.isNotBlank() }?.text
                    ?: "Открыть текст"
            available != null -> "Открыть текст"
            state.loadingLyrics -> "Загружаем текст…"
            failed -> "Не удалось загрузить. Повторить"
            state.lyrics != null -> "Текст недоступен"
            else -> "Загрузить текст"
        }
    val openQueue by rememberUpdatedState(onQueue)
    val queueModifier =
        Modifier.pointerInput(Unit) {
                var distance = 0f
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (distance < -24.dp.toPx()) openQueue()
                        distance = 0f
                    },
                    onDragCancel = { distance = 0f },
                ) { change, amount ->
                    change.consume()
                    distance += amount
                }
            }
            .clickable(interactionSource = null, indication = null, onClick = onQueue)
            .semantics { contentDescription = "Открыть очередь" }
    val lyricsModifier =
        Modifier.clickable(enabled = !state.loadingLyrics) {
            if (available != null) onLyrics() else onRetryLyrics()
        }
    if (horizontal) {
        val rowHeight =
            with(LocalDensity.current) {
                maxOf(48.dp, MaterialTheme.typography.bodyLarge.fontSize.toDp() * 1.5f)
            }
        Column(modifier.fillMaxWidth().height(rowHeight + 8.dp).testTag("player-lyrics-footer")) {
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = colors.onSurface.copy(alpha = 0.18f),
            )
            Row(
                Modifier.fillMaxWidth().height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QueueGlyph(queueModifier.width(64.dp).fillMaxHeight())
                Row(
                    lyricsModifier.weight(1f).fillMaxHeight().padding(end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Текст:",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        line,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color =
                            if (available != null) colors.onSurface else colors.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        val textHeight = 48.dp * LocalDensity.current.fontScale + 16.dp
        val topGap = if (spacious) 8.dp else 0.dp
        val bottomGap = if (spacious) 40.dp else 8.dp
        Column(
            modifier
                .fillMaxWidth()
                .height(topGap + 48.dp + textHeight + bottomGap)
                .testTag("player-lyrics-footer")
        ) {
            Spacer(Modifier.height(topGap))
            QueueGlyph(queueModifier.fillMaxWidth().height(48.dp))
            Column(lyricsModifier.fillMaxWidth().height(textHeight).padding(horizontal = 16.dp)) {
                HorizontalDivider(color = colors.onSurface.copy(alpha = 0.18f))
                Text(
                    "Текст:",
                    Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    line,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (available != null) colors.onSurface else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(bottomGap))
        }
    }
}

@Composable
private fun QueueGlyph(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(AppIcons.Queue, null, Modifier.size(24.dp).offset(y = (-3).dp))
        Icon(AppIcons.Queue, null, Modifier.size(24.dp).offset(y = 3.dp))
    }
}
