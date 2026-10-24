package app.musicplayer.ui.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.PlaylistOccurrence
import app.musicplayer.model.Artwork
import app.musicplayer.model.ArtworkKind
import app.musicplayer.model.Track
import app.musicplayer.ui.components.*
import app.musicplayer.ui.theme.*

private val placeholderArtwork = Artwork(ArtworkKind.PLACEHOLDER, null, null)

@Composable
fun PublicPlaylistScreen(
    model: PublicPlaylistModel,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onMenu: (Track) -> Unit,
) {
    val state by model.state.collectAsState()
    val rows = state.tracks.data?.items.orEmpty()
    val first = rows.firstOrNull { !it.unavailable && it.track != null }
    val listState = key(state.ref?.source) { rememberLazyListState() }
    val nearEnd by
        remember(listState, rows.size) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= 0 && last >= rows.size - 4
            }
        }
    LaunchedEffect(
        state.ref?.source,
        rows.size,
        nearEnd,
        state.tracks.data?.next,
        state.moreLoading,
        state.moreError,
    ) {
        if (
            nearEnd &&
                state.tracks.data?.next != null &&
                !state.moreLoading &&
                state.moreError == null
        )
            model.more()
    }
    val colors = rememberArtworkColors(state.card?.artwork)
    Box(Modifier.fillMaxSize()) {
        ArtworkAmbientBackground(colors, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("", onBack)
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            ) {
                item(key = "header") {
                    CollectionHeader(
                        title = state.card?.ref?.title ?: state.ref?.title.orEmpty(),
                        countLabel =
                            state.card?.count?.let { "Записей: $it" }
                                ?: "Загружено записей: ${rows.size}",
                        artwork = { modifier ->
                            TrackArtwork(
                                state.card?.artwork ?: placeholderArtwork,
                                artwork,
                                modifier,
                            )
                        },
                        onPlay =
                            if (first == null || state.tracks.loading) null
                            else ({ model.play(first.identity.ordinal) }),
                        onShuffle =
                            if (first == null || state.tracks.loading) null
                            else model::playShuffled,
                        supportingContent = {
                            state.card
                                ?.owner
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    Text(
                                        "Автор: $it",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            state.ref?.source?.provider?.let {
                                Text(
                                    if (it == "youtube") "YouTube Music" else it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (state.tracks.loading)
                    item(key = "initial-loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                state.tracks.error?.let {
                    item(key = "initial-error") { InlineError(it, model::retry) }
                }
                state.commandError?.let { item(key = "play-error") { InlineError(it) } }
                items(rows, key = { it.identity.ordinal }) { occurrence ->
                    val track = occurrence.track
                    if (track != null && !occurrence.unavailable)
                        TrackRow(
                            track,
                            artwork,
                            onPlay = { model.play(occurrence.identity.ordinal) },
                            onMenu = { onMenu(track) },
                            showAlbum = false,
                            trailingLabel =
                                if (track.durationMs?.let { it > 0 } == true) null else "",
                        )
                    else UnavailablePlaylistRow(occurrence, artwork)
                }
                if (
                    state.tracks.data != null &&
                        state.tracks.data?.next == null &&
                        rows.isEmpty() &&
                        !state.tracks.loading &&
                        state.tracks.error == null
                )
                    item(key = "empty") {
                        Text(
                            "В плейлисте пока нет записей",
                            Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                if (state.moreLoading)
                    item(key = "more-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.moreError?.let { item(key = "more-error") { InlineError(it, model::retry) } }
                if (state.tracks.data?.next != null && state.moreError == null)
                    item(key = "more") {
                        TextButton(onClick = model::more, enabled = !state.moreLoading) {
                            Text(if (state.moreLoading) "Загружаем…" else "Ещё записи")
                        }
                    }
            }
        }
    }
}

@Composable
private fun UnavailablePlaylistRow(occurrence: PlaylistOccurrence, artwork: ArtworkLoader) {
    val track = occurrence.track
    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { disabled() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(track?.artwork ?: placeholderArtwork, artwork, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track?.title ?: "Недоступный трек",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            track?.let {
                val artists = trackPresentation(it).artists.joinToString { artist -> artist.name }
                if (artists.isNotBlank())
                    Text(
                        artists,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Text(
                "Недоступно для воспроизведения",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
