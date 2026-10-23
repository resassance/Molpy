package app.musicplayer.collections

import app.musicplayer.model.*

@JvmInline
value class PlaylistId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class PlaylistItemId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class PlaylistSummary(
    val id: PlaylistId,
    val name: String,
    val trackCount: Int,
    val artworks: List<Artwork>,
)

data class PlaylistItem(val id: PlaylistItemId, val position: Int, val track: Track)

data class PlaylistDetail(val id: PlaylistId, val name: String, val items: List<PlaylistItem>)

data class SavedAlbum(
    val key: String,
    val ref: AlbumRef,
    val artwork: Artwork,
    val tracks: List<Track>,
)

data class SavedArtist(val key: String, val ref: ArtistRef, val tracks: List<Track>)

data class LibraryCollections(
    val favorites: List<Track> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val artistRevision: Long = 0,
)
