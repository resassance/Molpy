package app.musicplayer.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.model.ArtworkKind
import app.musicplayer.model.Track
import app.musicplayer.ui.components.*
import app.musicplayer.ui.theme.*

enum class LibrarySection(val title: String) {
    FAVORITES("Избранное"),
    PLAYLISTS("Плейлисты"),
    ALBUMS("Альбомы"),
    ARTISTS("Артисты"),
}

@Composable
fun CollectionListScreen(
    section: LibrarySection,
    state: LoadState<LibraryCollections>,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMenu: (Track) -> Unit,
    onPlaylist: (PlaylistSummary) -> Unit,
    onAlbum: (SavedAlbum) -> Unit,
    onArtist: (SavedArtist) -> Unit,
    onCreate: () -> Unit,
    onShuffle: ((List<Track>) -> Unit)? = null,
) {
    val data = state.data ?: LibraryCollections()
    val colors =
        rememberArtworkColors(
            if (section == LibrarySection.FAVORITES)
                data.favorites.firstOrNull { it.artwork.kind != ArtworkKind.PLACEHOLDER }?.artwork
            else null
        )
    Box(Modifier.fillMaxSize()) {
        if (section == LibrarySection.FAVORITES)
            ArtworkAmbientBackground(colors, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            CompactHeader(
                if (section == LibrarySection.FAVORITES) "" else section.title,
                onBack,
                if (section == LibrarySection.PLAYLISTS) {
                    { IconButton(onCreate) { Icon(AppIcons.Plus, "Создать плейлист") } }
                } else null,
            )
            if (section == LibrarySection.FAVORITES) {
                LazyColumn(
                    Modifier.weight(1f),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    item(key = "header") {
                        CollectionHeader(
                            section.title,
                            trackCount(data.favorites.size),
                            artwork = { modifier ->
                                if (data.favorites.isEmpty())
                                    Surface(modifier) {
                                        Box(
                                            contentAlignment = androidx.compose.ui.Alignment.Center
                                        ) {
                                            Icon(AppIcons.Heart, null, Modifier.size(40.dp))
                                        }
                                    }
                                else
                                    PlaylistCollage(
                                        data.favorites.map { it.artwork }.distinct().take(4),
                                        artwork,
                                        modifier,
                                    )
                            },
                            onPlay =
                                if (data.favorites.isEmpty()) null
                                else ({ onPlay(data.favorites, 0) }),
                            onShuffle =
                                if (data.favorites.isEmpty() || onShuffle == null) null
                                else ({ onShuffle(data.favorites) }),
                        )
                    }
                    if (state.loading)
                        item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    itemsIndexed(data.favorites, key = { _, track -> track.id.value }) {
                        index,
                        track ->
                        TrackRow(
                            track,
                            artwork,
                            { onPlay(data.favorites, index) },
                            { onMenu(track) },
                            showAlbum = false,
                        )
                    }
                    if (data.favorites.isEmpty()) item(key = "empty") { EmptyCollection() }
                    state.error?.let { item(key = "error") { InlineError(it) } }
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(140.dp),
                    Modifier.weight(1f),
                    state = rememberLazyGridState(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.loading)
                        item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    state.error?.let {
                        item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            InlineError(it)
                        }
                    }
                    when (section) {
                        LibrarySection.PLAYLISTS ->
                            items(data.playlists, key = { it.id.value }) { playlist ->
                                CollectionTile(
                                    playlist.name,
                                    trackCount(playlist.trackCount),
                                    { onPlaylist(playlist) },
                                ) {
                                    PlaylistCollage(playlist.artworks, artwork, it)
                                }
                            }
                        LibrarySection.ALBUMS ->
                            items(data.albums, key = { it.key }) { album ->
                                CollectionTile(
                                    album.ref.name,
                                    album.tracks
                                        .firstOrNull()
                                        ?.let { trackPresentation(it).artists }
                                        .orEmpty()
                                        .joinToString { it.name },
                                    { onAlbum(album) },
                                ) {
                                    TrackArtwork(album.artwork, artwork, it)
                                }
                            }
                        LibrarySection.ARTISTS ->
                            items(data.artists, key = { it.key }) { artist ->
                                CollectionTile(artist.ref.name, null, { onArtist(artist) }) {
                                    ArtistPhoto(artist.ref.name, artist.ref.source, artwork, it)
                                }
                            }
                        LibrarySection.FAVORITES -> Unit
                    }
                    val empty =
                        when (section) {
                            LibrarySection.PLAYLISTS -> data.playlists.isEmpty()
                            LibrarySection.ALBUMS -> data.albums.isEmpty()
                            LibrarySection.ARTISTS -> data.artists.isEmpty()
                            LibrarySection.FAVORITES -> false
                        }
                    if (empty)
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyCollection()
                        }
                }
            }
        }
    }
}

@Composable
private fun EmptyCollection() {
    Text(
        "Здесь пока пусто",
        Modifier.padding(vertical = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
