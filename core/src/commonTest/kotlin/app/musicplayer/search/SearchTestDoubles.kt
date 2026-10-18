package app.musicplayer.search

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.model.*
import kotlin.coroutines.*

internal fun track(id: String = "reference", title: String = "Numb", artist: String = "Linkin Park",
                   duration: Long? = 187000, album: String? = null, version: String = "",
                   tentative: Boolean = false): Track {
    val source = SourceRef("youtube", "video", id)
    val metadata = if (tentative) source else SourceRef("youtube_music", "song", "music-$id")
    return Track(TrackId(id), title, listOf(ArtistRef(artist, null)), album?.let { AlbumRef(it, null) },
        Artwork(if (tentative) ArtworkKind.VIDEO else ArtworkKind.RELEASE, null, null), duration,
        setOf(source), MetadataProvenance(metadata, 123, tentative),
        listOf(PlaybackCandidate(CandidateId(id), source, version, duration, MatchConfidence.TENTATIVE)))
}

internal class FakeCatalog : CatalogPort {
    var songs: AppResult<List<Track>> = AppResult.Success(emptyList())
    var videos: AppResult<List<Track>> = AppResult.Success(emptyList())
    var matches: AppResult<List<Track>> = AppResult.Success(emptyList())
    var songAction: (() -> AppResult<List<Track>>)? = null
    var videoAction: (() -> AppResult<List<Track>>)? = null
    var matchAction: (() -> AppResult<List<Track>>)? = null
    var songCalls = 0
    var videoCalls = 0
    var matchCalls = 0
    val matchedTracks = mutableListOf<Track>()
    override suspend fun searchSongs(query: String): AppResult<List<Track>> { songCalls++; return songAction?.invoke() ?: songs }
    override suspend fun searchVideos(query: String): AppResult<List<Track>> { videoCalls++; return videoAction?.invoke() ?: videos }
    override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> {
        matchCalls++; matchedTracks += track; return matchAction?.invoke() ?: matches
    }
}

// These boundary fakes complete synchronously; real async scheduling is outside this contract.
internal fun <T> runSuspend(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { completed = result }
    })
    return checkNotNull(completed) { "Test boundary unexpectedly suspended" }.getOrThrow()
}
