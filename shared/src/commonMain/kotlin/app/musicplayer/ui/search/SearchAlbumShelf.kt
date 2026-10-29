package app.musicplayer.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.SearchItem
import app.musicplayer.model.AlbumRef
import app.musicplayer.ui.components.CollectionTile
import app.musicplayer.ui.components.TrackArtwork

@Composable
internal fun SearchAlbumShelf(
    items: List<SearchItem.AlbumItem>,
    artwork: ArtworkLoader,
    onAlbum: (AlbumRef) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileWidth = ((maxWidth - 12.dp) / 2).coerceIn(136.dp, 200.dp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item ->
                CollectionTile(
                    item.card.album.name,
                    item.card.artists.joinToString(" · ") { it.name },
                    { onAlbum(item.card.album) },
                    Modifier.width(tileWidth),
                    titleMaxLines = 2,
                ) { modifier ->
                    TrackArtwork(item.card.artwork, artwork, modifier)
                }
            }
        }
    }
}
