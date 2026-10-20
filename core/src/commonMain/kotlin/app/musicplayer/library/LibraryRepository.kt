package app.musicplayer.library

import app.musicplayer.catalog.IdentityStore
import app.musicplayer.lyrics.LyricsCache
import app.musicplayer.model.*
import kotlinx.coroutines.flow.Flow

interface LibraryRepository : IdentityStore, LyricsCache {
    suspend fun upsert(track: Track)
    suspend fun getTrack(id: TrackId): Track?
    suspend fun setFavorite(id: TrackId, favorite: Boolean)
    suspend fun saveQueue(snapshot: QueueSnapshot)
    suspend fun restoreQueue(): QueueSnapshot
    suspend fun recordStarted(id: TrackId, atMs: Long)
    suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>?
    suspend fun cacheSearch(query: String, musicOnly: Boolean, tracks: List<Track>, atMs: Long)
    suspend fun prune(nowMs: Long)
    suspend fun getNetworkConfig(): NetworkConfig
    suspend fun saveNetworkConfig(config: NetworkConfig)
    val favorites: Flow<List<Track>>
    val recent: Flow<List<Track>>
}
