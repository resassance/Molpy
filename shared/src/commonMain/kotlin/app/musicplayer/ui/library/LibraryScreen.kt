package app.musicplayer.ui.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
    onPlayFavorites: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val data = state.data ?: LibraryCollections()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onProfile != null) IconButton(onProfile) { Icon(AppIcons.Profile, "Профиль") }
                Text(
                    "Медиатека",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onSearch != null) IconButton(onSearch) { Icon(AppIcons.Search, "Поиск") }
                if (onSettings != null)
                    IconButton(onSettings) { Icon(AppIcons.Settings, "Настройки") }
            }
        }
        if (state.loading)
            item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item(key = "error") { InlineError(it, onRetry) } }
        item(key = "collections") {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).clickable(onClick = onFavorites),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCollage(
                                data.favorites.map { it.artwork }.distinct().take(4),
                                artwork,
                                Modifier.size(64.dp),
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(
                                    "Избранное",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    trackCount(data.favorites.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledIconButton(
                            onClick = { onPlayFavorites?.invoke() },
                            enabled = data.favorites.isNotEmpty() && onPlayFavorites != null,
                            modifier = Modifier.size(48.dp),
                            colors =
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface,
                                ),
                        ) {
                            Icon(AppIcons.Play, "Воспроизвести избранное", Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val measurer = rememberTextMeasurer()
                        val labelStyle = MaterialTheme.typography.labelMedium
                        val widestLabel =
                            with(LocalDensity.current) {
                                listOf("Плейлисты", "Загрузки", "Альбомы", "Артисты")
                                    .maxOf {
                                        measurer
                                            .measure(AnnotatedString(it), style = labelStyle)
                                            .size
                                            .width
                                    }
                                    .toDp()
                            }
                        val fourColumns = (maxWidth - 24.dp) / 4 >= widestLabel
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LibraryShortcut(
                                    "Плейлисты",
                                    AppIcons.QueueAdd,
                                    onPlaylists,
                                    Modifier.weight(1f),
                                )
                                LibraryShortcut(
                                    "Загрузки",
                                    AppIcons.Download,
                                    onDownloads,
                                    Modifier.weight(1f),
                                    "${trackCount(downloadCount)} · ${formatBytes(downloadBytes)}",
                                )
                                if (fourColumns) {
                                    LibraryShortcut(
                                        "Альбомы",
                                        AppIcons.Library,
                                        onAlbums,
                                        Modifier.weight(1f),
                                    )
                                    LibraryShortcut(
                                        "Артисты",
                                        AppIcons.Profile,
                                        onArtists,
                                        Modifier.weight(1f),
                                    )
                                }
                            }
                            if (!fourColumns)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    LibraryShortcut(
                                        "Альбомы",
                                        AppIcons.Library,
                                        onAlbums,
                                        Modifier.weight(1f),
                                    )
                                    LibraryShortcut(
                                        "Артисты",
                                        AppIcons.Profile,
                                        onArtists,
                                        Modifier.weight(1f),
                                    )
                                }
                        }
                    }
                }
            }
        }
        item(key = "playlists-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onPlaylists, Modifier.weight(1f)) {
                    Text(
                        "Плейлисты",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onCreate) { Icon(AppIcons.Plus, "Создать плейлист") }
            }
        }
        item(key = "playlists") {
            if (data.playlists.isEmpty()) TextButton(onCreate) { Text("Создать первый плейлист") }
            else
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.playlists.take(6), key = { it.id.value }) { playlist ->
                        CollectionTile(
                            playlist.name,
                            trackCount(playlist.trackCount),
                            { onPlaylist(playlist) },
                            Modifier.width(148.dp),
                        ) {
                            PlaylistCollage(playlist.artworks, artwork, it)
                        }
                    }
                }
        }
        item(key = "artists-header") { LibraryShelfHeader("Артисты", onArtists) }
        item(key = "artists") {
            if (data.artists.isEmpty())
                Text(
                    "Артисты из вашей музыки появятся здесь",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.artists.take(6), key = { it.key }) { artist ->
                        CollectionTile(
                            artist.ref.name,
                            null,
                            { onArtist(artist) },
                            Modifier.width(92.dp),
                        ) {
                            ArtistPhoto(artist.ref.name, artist.ref.source, artwork, it)
                        }
                    }
                }
        }
        item(key = "albums-header") { LibraryShelfHeader("Альбомы", onAlbums) }
        item(key = "albums") {
            if (data.albums.isEmpty())
                Text(
                    "Сохранённые альбомы появятся здесь",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.albums.take(6), key = { it.key }) { album ->
                        CollectionTile(
                            album.ref.name,
                            null,
                            { onAlbum(album) },
                            Modifier.width(92.dp),
                        ) {
                            TrackArtwork(album.artwork, artwork, it)
                        }
                    }
                }
        }
    }
}

@Composable
private fun LibraryShelfHeader(title: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth()) {
        Text(
            title,
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LibraryShortcut(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    description: String? = null,
) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, description, Modifier.size(28.dp))
            }
        }
        Text(
            title,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
