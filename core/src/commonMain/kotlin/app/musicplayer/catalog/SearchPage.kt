package app.musicplayer.catalog

import app.musicplayer.model.*

enum class SearchFilter {
    ALL,
    TRACKS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
}

sealed interface SearchItem {
    val source: SourceRef
    val filter: SearchFilter
    val key: String
        get() =
            "${when (this) { is TrackItem -> "track"
 is ArtistItem -> "artist"
 is AlbumItem -> "album"
 is PlaylistItem -> "playlist" }}:${source.provider}/${source.kind}/${source.id}"

    data class TrackItem(val track: Track, override val source: SourceRef) : SearchItem {
        override val filter = SearchFilter.TRACKS
    }

    data class ArtistItem(val ref: ArtistRef, val artwork: Artwork?) : SearchItem {
        override val source
            get() = requireNotNull(ref.source)

        override val filter = SearchFilter.ARTISTS
    }

    data class AlbumItem(val card: AlbumCard) : SearchItem {
        override val source
            get() = requireNotNull(card.album.source)

        override val filter = SearchFilter.ALBUMS
    }

    data class PlaylistItem(val card: PublicPlaylistCard) : SearchItem {
        override val source
            get() = card.ref.source

        override val filter = SearchFilter.PLAYLISTS
    }
}

data class SearchSection(val filter: SearchFilter, val items: List<SearchItem>)

data class SearchPage(
    val topResult: SearchItem?,
    val sections: List<SearchSection>,
    val next: CatalogCursor?,
    val primaryFailure: AppError? = null,
) {
    fun items(): List<SearchItem> =
        (listOfNotNull(topResult) + sections.flatMap { it.items }).distinctBy { it.key }

    fun tracks(): List<Track> = items().filterIsInstance<SearchItem.TrackItem>().map { it.track }

    fun withTracks(tracks: List<Track>): SearchPage {
        val updates = tracks.associateBy { it.id }
        fun update(item: SearchItem): SearchItem =
            if (item is SearchItem.TrackItem)
                item.copy(track = updates[item.track.id] ?: item.track)
            else item
        return copy(
            topResult = topResult?.let(::update),
            sections = sections.map { it.copy(items = it.items.map(::update)) },
        )
    }
}
