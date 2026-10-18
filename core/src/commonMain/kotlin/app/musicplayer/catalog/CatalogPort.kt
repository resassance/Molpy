package app.musicplayer.catalog

import app.musicplayer.model.AppResult
import app.musicplayer.model.Track

/** Provider responses end at this boundary; absence and provider failure remain distinct. */
interface CatalogPort {
    suspend fun searchSongs(query: String): AppResult<List<Track>>
    suspend fun searchVideos(query: String): AppResult<List<Track>>
    suspend fun findMusicMatches(track: Track): AppResult<List<Track>>
}
