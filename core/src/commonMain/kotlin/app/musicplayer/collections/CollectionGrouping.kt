package app.musicplayer.collections

import app.musicplayer.artists.*
import app.musicplayer.model.*

object CollectionGrouping {
    fun artistKey(track: Track, artistIndex: Int): String =
        track.artists[artistIndex].source?.let(::sourceKey)
            ?: "artist/local/${part(track.id.value)}/$artistIndex"

    fun albumKey(track: Track): String? =
        track.album?.let { album ->
            album.source?.let(::sourceKey) ?: "album/local/${part(track.id.value)}"
        }

    fun build(tracks: List<Track>): Pair<List<SavedAlbum>, List<SavedArtist>> {
        val unique = tracks.distinctBy { it.id }
        val albums =
            unique
                .filter { it.album != null }
                .groupBy { requireNotNull(albumKey(it)) }
                .map { (key, items) ->
                    SavedAlbum(
                        key,
                        requireNotNull(items.first().album),
                        items.minBy { it.artwork.kind.ordinal }.artwork,
                        items,
                    )
                }
        val artists = linkedMapOf<String, Pair<ArtistRef, MutableList<Track>>>()
        for (track in unique) {
            track.artists.forEachIndexed { index, artist ->
                val group = artists.getOrPut(artistKey(track, index)) { artist to mutableListOf() }
                if (group.second.none { it.id == track.id }) group.second += track
            }
        }
        return albums to
            artists.map { (key, value) -> SavedArtist(key, value.first, value.second.toList()) }
    }

    fun build(
        tracks: List<Track>,
        directory: ArtistDirectory,
    ): Pair<List<SavedAlbum>, List<SavedArtist>> {
        val albums = build(tracks).first
        val groups = linkedMapOf<String, Pair<ArtistRef, MutableList<Track>>>()
        for (track in tracks.distinctBy { it.id }) for (credit in
            ArtistResolver.present(track, directory).artists) {
            val key = credit.id?.value ?: credit.alias.value
            val group =
                groups.getOrPut(key) { ArtistRef(credit.name, credit.source) to mutableListOf() }
            group.second.add(track)
        }
        return albums to
            groups.map { (key, value) -> SavedArtist(key, value.first, value.second.toList()) }
    }

    private fun sourceKey(source: SourceRef) =
        "source/${part(source.provider)}/${part(source.kind)}/${part(source.id)}"

    private fun part(value: String) = "${value.length}:$value"
}
