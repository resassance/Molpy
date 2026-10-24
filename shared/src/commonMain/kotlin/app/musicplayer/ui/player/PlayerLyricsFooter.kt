package app.musicplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    Column(
        modifier
            .fillMaxWidth()
            .height(48.dp + 48.dp * LocalDensity.current.fontScale)
            .testTag("player-lyrics-footer")
    ) {
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
                .clickable(interactionSource = null, indication = null, onClick = onQueue)
                .semantics { contentDescription = "Открыть очередь" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Queue, null, Modifier.size(24.dp).offset(y = (-3).dp))
            Icon(AppIcons.Queue, null, Modifier.size(24.dp).offset(y = 3.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp).clickable(
                enabled = !state.loadingLyrics
            ) {
                if (available != null) onLyrics() else onRetryLyrics()
            }
        ) {
            HorizontalDivider(color = colors.onSurface.copy(alpha = 0.18f))
            Text(
                "Текст:",
                Modifier.padding(top = 8.dp),
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
    }
}
