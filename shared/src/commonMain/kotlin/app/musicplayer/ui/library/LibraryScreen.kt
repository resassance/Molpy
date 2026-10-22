package app.musicplayer.ui.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.ui.components.*

@Composable
fun LibraryScreen(
    state: LoadState<LibraryCollections>,
    artwork: ArtworkLoader,
    downloadCount: Int,
    downloadBytes: Long,
    onFavorites: () -> Unit,
    onPlaylists: () -> Unit,
    onCreate: () -> Unit,
    onPlaylist: (PlaylistSummary) -> Unit,
    onAlbums: () -> Unit,
    onAlbum: (SavedAlbum) -> Unit,
    onArtists: () -> Unit,
    onArtist: (SavedArtist) -> Unit,
    onDownloads: () -> Unit,
    onRetry: () -> Unit,
) {
    val data = state.data ?: LibraryCollections()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        item {
            Text(
                "Медиатека",
                Modifier.padding(top = 16.dp, bottom = 20.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { InlineError(it, onRetry) } }
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onFavorites).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.HeartFilled,
                    null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        "Избранное",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        trackCount(data.favorites.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(AppIcons.Down, null, Modifier.rotate(-90f))
            }
            HorizontalDivider(
                Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clickable(onClick = onPlaylists)) {
                    SectionHeader("Плейлисты")
                }
                IconButton(onCreate) { Icon(AppIcons.Plus, "Создать плейлист") }
            }
            if (data.playlists.isEmpty()) TextButton(onCreate) { Text("Создать первый плейлист") }
            else
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(data.playlists.take(6), key = { it.id.value }) { playlist ->
                        Row(
                            Modifier.width(164.dp).clickable { onPlaylist(playlist) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCollage(playlist.artworks, artwork, Modifier.size(76.dp))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    trackCount(playlist.trackCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            SectionHeader("Альбомы", "Все", onAlbums)
            if (data.albums.isEmpty())
                Text(
                    "Сохранённые альбомы появятся здесь",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.albums.take(6), key = { it.key }) { album ->
                        Column(Modifier.width(104.dp).clickable { onAlbum(album) }) {
                            TrackArtwork(album.artwork, artwork, Modifier.size(104.dp))
                            Text(
                                album.ref.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                album.tracks.firstOrNull()?.artists.orEmpty().joinToString {
                                    it.name
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            SectionHeader("Артисты", "Все", onArtists)
            if (data.artists.isEmpty())
                Text(
                    "Артисты из вашей музыки появятся здесь",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.artists.take(6), key = { it.key }) { artist ->
                        Column(
                            Modifier.width(104.dp).clickable { onArtist(artist) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ArtistAvatar(artist.ref.name, Modifier.size(64.dp))
                            Text(
                                artist.ref.name,
                                Modifier.padding(top = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            HorizontalDivider(
                Modifier.padding(top = 24.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onDownloads).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Download,
                    null,
                    Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Загрузки", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${trackCount(downloadCount)} · ${formatBytes(downloadBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(AppIcons.Down, null, Modifier.rotate(-90f))
            }
        }
    }
}
