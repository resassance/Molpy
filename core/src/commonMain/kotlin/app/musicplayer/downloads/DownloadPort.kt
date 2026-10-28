package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.flow.StateFlow

/** Transfers start only from explicit user commands; queue/library changes do not enqueue work. */
interface DownloadPort {
    val states: StateFlow<List<OfflineAsset>>
    /** Runtime-only normalized failures; no upstream exceptions, URLs or credentials. */
    val failures: StateFlow<Map<AssetId, AppError>>
    val preparations: StateFlow<List<DownloadPreparation>>
    val activity: StateFlow<Map<AssetId, DownloadActivity>>

    suspend fun cancelPreparation(id: String): AppResult<Unit>

    suspend fun enqueue(track: Track, candidate: PlaybackCandidate): AppResult<AssetId>

    suspend fun pause(id: AssetId): AppResult<Unit>

    suspend fun resume(id: AssetId): AppResult<Unit>

    suspend fun cancel(id: AssetId): AppResult<Unit>

    suspend fun remove(id: AssetId): AppResult<Unit>

    suspend fun removeAll(): AppResult<Unit>
}
