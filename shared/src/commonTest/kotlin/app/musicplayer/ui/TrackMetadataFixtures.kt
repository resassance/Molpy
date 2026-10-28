package app.musicplayer.ui

import app.musicplayer.catalog.*
import app.musicplayer.model.*

internal fun durationMetadata(
    library: EngineeringLibrary = EngineeringLibrary(),
    fetch: suspend (List<SourceRef>) -> AppResult<Map<SourceRef, Long>>,
) =
    TrackMetadataLoader(
        library,
        object : CatalogPort {
            override suspend fun durations(sources: List<SourceRef>) = fetch(sources)

            override suspend fun searchSongs(query: String): AppResult<List<Track>> =
                error("No search")

            override suspend fun searchVideos(query: String): AppResult<List<Track>> =
                error("No audio lookup")

            override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> =
                error("No matching")
        },
    )

internal fun unknownDurationTrack(name: String = "missing") =
    sampleTrack(name).let {
        it.copy(
            durationMs = null,
            candidates = it.candidates.map { c -> c.copy(durationMs = null) },
        )
    }
