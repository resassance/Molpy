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
import app.musicplayer.model.*
import app.musicplayer.ui.components.trackPresentation

@Composable
fun LyricsScreen(state: PlayerState, onClose: () -> Unit, onRetry: () -> Unit) {
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
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClose) { Icon(app.musicplayer.ui.components.AppIcons.Back, "Назад") }
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(
                        state.currentTrack?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.currentTrack
                            ?.let { trackPresentation(it).artists }
                            .orEmpty()
                            .joinToString { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.loadingLyrics ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                lyrics != null && lyrics.kind == LyricsKind.LINE_SYNCED ->
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = list,
                        contentPadding =
                            PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(lyrics.lines) { index, line ->
                            Text(
                                line.text,
                                fontSize = 28.sp,
                                lineHeight = 36.sp,
                                fontWeight =
                                    if (index == active) FontWeight.Bold else FontWeight.Medium,
                                color =
                                    if (index == active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                lyrics != null ->
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp)) {
                        item {
                            Text(lyrics.plainText.orEmpty(), fontSize = 20.sp, lineHeight = 32.sp)
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
        }
    }
}
