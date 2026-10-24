package app.musicplayer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*

internal fun SearchFilter.label(): String =
    when (this) {
        SearchFilter.ALL -> "Всё"
        SearchFilter.TRACKS -> "Треки"
        SearchFilter.ARTISTS -> "Артисты"
        SearchFilter.ALBUMS -> "Альбомы"
        SearchFilter.PLAYLISTS -> "Плейлисты"
    }

internal fun SearchFilter.allLabel(): String =
    when (this) {
        SearchFilter.ALL -> "Все результаты"
        SearchFilter.TRACKS -> "Все треки"
        SearchFilter.ARTISTS -> "Все артисты"
        SearchFilter.ALBUMS -> "Все альбомы"
        SearchFilter.PLAYLISTS -> "Все плейлисты"
    }

@Composable
fun SearchResultRow(
    item: SearchItem,
    artwork: ArtworkLoader,
    current: TrackId?,
    onPlay: (String) -> Unit,
    onMenu: (Track) -> Unit,
    onArtist: (ArtistRef) -> Unit,
    onAlbum: (AlbumRef) -> Unit,
    onPublicPlaylist: (PublicPlaylistRef) -> Unit,
) {
    if (item is SearchItem.TrackItem) {
        Column {
            Text(
                "Трек",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TrackRow(
                item.track,
                artwork,
                { onPlay(item.key) },
                { onMenu(item.track) },
                selected = item.track.id == current,
            )
        }
        return
    }
    val title: String
    val subtitle: String
    val image: Artwork?
    val open: () -> Unit
    when (item) {
        is SearchItem.ArtistItem -> {
            title = item.ref.name
            subtitle = "Артист"
            image = item.artwork
            open = { onArtist(item.ref) }
        }
        is SearchItem.AlbumItem -> {
            title = item.card.album.name
            subtitle = (listOf("Альбом") + item.card.artists.map { it.name }).joinToString(" · ")
            image = item.card.artwork
            open = { onAlbum(item.card.album) }
        }
        is SearchItem.PlaylistItem -> {
            title = item.card.ref.title
            subtitle =
                listOfNotNull("Плейлист", item.card.owner, item.card.count?.let { "Треков: $it" })
                    .joinToString(" · ")
            image = item.card.artwork
            open = { onPublicPlaylist(item.card.ref) }
        }
        is SearchItem.TrackItem -> return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item is SearchItem.ArtistItem)
            ArtistPhoto(
                item.ref.name,
                item.ref.source,
                artwork,
                Modifier.size(64.dp),
                initial = image,
            )
        else
            TrackArtwork(
                image ?: Artwork(ArtworkKind.PLACEHOLDER, null, null),
                artwork,
                Modifier.size(64.dp),
            )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
