package app.musicplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.model.Track
import app.musicplayer.ui.components.*

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
) {
    val data = state.data ?: LibraryCollections()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            section.title,
            onBack,
            if (section == LibrarySection.PLAYLISTS) {
                { IconButton(onCreate) { Icon(AppIcons.Plus, "Создать плейлист") } }
            } else null,
        )
        LazyColumn(
            Modifier.weight(1f),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (section) {
                LibrarySection.FAVORITES ->
                    itemsIndexed(data.favorites, key = { _, track -> track.id.value }) {
                        index,
                        track ->
                        TrackRow(
                            track,
                            artwork,
                            { onPlay(data.favorites, index) },
                            { onMenu(track) },
                        )
                    }
                LibrarySection.PLAYLISTS ->
                    items(data.playlists, key = { it.id.value }) { playlist ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPlaylist(playlist) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCollage(playlist.artworks, artwork, Modifier.size(64.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(playlist.name)
                                Text(
                                    trackCount(playlist.trackCount),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                LibrarySection.ALBUMS ->
                    items(data.albums, key = { it.key }) { album ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onAlbum(album) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TrackArtwork(album.artwork, artwork, Modifier.size(64.dp))
                            Text(album.ref.name, Modifier.padding(start = 12.dp))
                        }
                    }
                LibrarySection.ARTISTS ->
                    items(data.artists, key = { it.key }) { artist ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onArtist(artist) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtistAvatar(artist.ref.name, Modifier.size(64.dp))
                            Text(artist.ref.name, Modifier.padding(start = 12.dp))
                        }
                    }
            }
            val empty =
                when (section) {
                    LibrarySection.FAVORITES -> data.favorites.isEmpty()
                    LibrarySection.PLAYLISTS -> data.playlists.isEmpty()
                    LibrarySection.ALBUMS -> data.albums.isEmpty()
                    LibrarySection.ARTISTS -> data.artists.isEmpty()
                }
            if (empty)
                item {
                    Text(
                        "Здесь пока пусто",
                        Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            state.error?.let { item { InlineError(it) } }
        }
    }
}
