package app.musicplayer.ui.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.profile.formatListeningTime
import app.musicplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    model: CatalogModel,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onMenu: (Track) -> Unit,
    onAlbum: (AlbumRef) -> Unit,
    onMerge: (() -> Unit)? = null,
    onMerges: (() -> Unit)? = null,
    playedMs: Long? = null,
    onShuffle: ((List<Track>) -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    val colors = rememberArtworkColors(if (state.albumRef != null) state.artwork else null)
    Box(Modifier.fillMaxSize()) {
        if (state.albumRef != null) ArtworkAmbientBackground(colors, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val releaseColumns = maxOf(2, ((maxWidth - 16.dp) / 156.dp).toInt())
            Column(Modifier.fillMaxSize()) {
                var menu by remember { mutableStateOf(false) }
                CompactHeader(
                    "",
                    onBack,
                    action =
                        if (onMerge == null) null
                        else {
                            {
                                Box {
                                    IconButton({ menu = true }) {
                                        Icon(AppIcons.More, "Действия с артистом")
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Объединить с…") },
                                            leadingIcon = { Icon(AppIcons.Plus, null) },
                                            onClick = {
                                                menu = false
                                                onMerge()
                                            },
                                        )
                                        onMerges?.let { action ->
                                            DropdownMenuItem(
                                                text = { Text("Объединённые карточки") },
                                                leadingIcon = { Icon(AppIcons.Edit, null) },
                                                onClick = {
                                                    menu = false
                                                    action()
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                )
                LazyColumn(
                    Modifier.weight(1f),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    item {
                        val tracks =
                            if (state.section == CatalogSection.FAVORITES) state.favoriteTracks
                            else state.allTracks()
                        val complete =
                            state.section == CatalogSection.FAVORITES ||
                                (!state.tracks.loading &&
                                    state.tracks.error == null &&
                                    state.tracks.data?.next == null &&
                                    (state.tracks.data != null ||
                                        (state.albumRef != null && state.localTracks.isNotEmpty())))
                        CollectionHeader(
                            title = state.title,
                            countLabel =
                                if (state.albumRef != null) {
                                    val artists =
                                        state.artists.map { artistName(it) }.joinToString()
                                    listOf(artists, trackCount(tracks.size))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                } else
                                    playedMs?.let { "Прослушано: ${formatListeningTime(it)}" }
                                        ?: "Прослушано: —",
                            artwork = { modifier ->
                                if (state.artistRef != null)
                                    ArtistPhoto(
                                        state.title,
                                        state.artistRef?.source,
                                        artwork,
                                        modifier,
                                    )
                                else
                                    TrackArtwork(
                                        state.artwork
                                            ?: Artwork(ArtworkKind.PLACEHOLDER, null, null),
                                        artwork,
                                        modifier,
                                    )
                            },
                            onPlay = if (tracks.isEmpty()) null else ({ model.play(0) }),
                            onShuffle =
                                if (tracks.isEmpty() || !complete || onShuffle == null) null
                                else ({ onShuffle(tracks) }),
                            supportingContent = {
                                if (state.albumRef == null && state.artists.isNotEmpty())
                                    Text(
                                        state.artists.map { artistName(it) }.joinToString(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            },
                        )
                    }
                    item(key = "filters") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CatalogSection.entries.forEach { section ->
                                FilterChip(
                                    selected = state.section == section,
                                    onClick = { model.selectSection(section) },
                                    label = {
                                        Text(
                                            if (section == CatalogSection.ALL) "Все"
                                            else "Избранное"
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    shape = CircleShape,
                                )
                            }
                        }
                    }
                    if (state.section == CatalogSection.FAVORITES) {
                        state.collectionError?.let { item { InlineError(it) } }
                        itemsIndexed(state.favoriteTracks, key = { _, track -> track.id.value }) {
                            index,
                            track ->
                            TrackRow(
                                track,
                                artwork,
                                { model.play(index) },
                                { onMenu(track) },
                                showAlbum = false,
                            )
                        }
                        if (state.favoriteTracks.isEmpty())
                            item {
                                Text(
                                    "Здесь пока нет избранных треков",
                                    Modifier.padding(vertical = 24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    } else {
                        if (state.tracks.loading)
                            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        state.tracks.error?.let { item { InlineError(it, model::retry) } }
                        itemsIndexed(
                            state.allTracks(),
                            key = { index, track -> "$index:${track.id.value}" },
                        ) { index, track ->
                            TrackRow(
                                track,
                                artwork,
                                { model.play(index) },
                                { onMenu(track) },
                                showAlbum = false,
                            )
                        }
                        if (state.tracks.data?.next != null)
                            item {
                                TextButton(
                                    { model.loadMore(CatalogPart.TRACKS) },
                                    enabled = !state.tracks.loading,
                                ) {
                                    Text("Ещё треки")
                                }
                            }
                        if (
                            !state.tracks.loading &&
                                state.tracks.error == null &&
                                state.allTracks().isEmpty()
                        )
                            item {
                                Text(
                                    "Доступных треков пока нет",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        releaseSection(
                            "Дискография",
                            state.albums,
                            artwork,
                            { model.loadMore(CatalogPart.ALBUMS) },
                            onAlbum,
                            releaseColumns,
                        )
                        releaseSection(
                            "Синглы и EP",
                            state.singles,
                            artwork,
                            { model.loadMore(CatalogPart.SINGLES) },
                            onAlbum,
                            releaseColumns,
                        )
                    }
                    state.commandError?.let { item { InlineError(it) } }
                }
            }
        }
    }
}

private fun LazyListScope.releaseSection(
    title: String,
    state: LoadState<CatalogPage<AlbumCard>>,
    artwork: ArtworkLoader,
    onMore: () -> Unit,
    onAlbum: (AlbumRef) -> Unit,
    columns: Int,
) {
    val releases = state.data?.items.orEmpty()
    if (releases.isNotEmpty()) {
        item(key = "release:$title") { SectionHeader(title) }
        itemsIndexed(
            releases.chunked(columns),
            key = { index, cards ->
                "$title:$index:${cards.first().album.source}"
            },
        ) { _, cards ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                cards.forEach { album ->
                    CollectionTile(
                        album.album.name,
                        album.artists
                            .map { artistName(it) }
                            .joinToString()
                            .takeIf { it.isNotBlank() },
                        { onAlbum(album.album) },
                        Modifier.weight(1f),
                    ) { modifier ->
                        TrackArtwork(album.artwork, artwork, modifier)
                    }
                }
                repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    state.error?.let { item(key = "release-error:$title") { InlineError(it, onMore) } }
    if (state.data?.next != null)
        item(key = "release-more:$title") {
            TextButton(onMore, enabled = !state.loading) {
                Text(if (state.loading) "Загружаем…" else "Ещё релизы")
            }
        }
}
