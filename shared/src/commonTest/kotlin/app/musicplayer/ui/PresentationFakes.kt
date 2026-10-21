package app.musicplayer.ui

import app.musicplayer.collections.*
import app.musicplayer.downloads.*
import app.musicplayer.model.*
import kotlinx.coroutines.flow.*

internal class MutableCollections : CollectionsRepository {
    val changes =
        MutableStateFlow<AppResult<LibraryCollections>>(AppResult.Success(LibraryCollections()))
    override val overview: Flow<AppResult<LibraryCollections>> = changes
    val playlistChanges = MutableStateFlow<AppResult<PlaylistDetail?>>(AppResult.Success(null))

    override fun playlist(id: PlaylistId): Flow<AppResult<PlaylistDetail?>> = playlistChanges

    override suspend fun create(name: String) = AppResult.Success(PlaylistId("new"))

    override suspend fun rename(id: PlaylistId, name: String) = AppResult.Success(Unit)

    override suspend fun delete(id: PlaylistId) = AppResult.Success(Unit)

    override suspend fun append(id: PlaylistId, track: Track) =
        AppResult.Success(PlaylistItemId("new-item"))

    override suspend fun move(id: PlaylistId, itemId: PlaylistItemId, toIndex: Int) =
        AppResult.Success(Unit)

    override suspend fun remove(id: PlaylistId, itemId: PlaylistItemId) = AppResult.Success(Unit)
}

internal open class PresentationDownloads : DownloadPort {
    override val states = MutableStateFlow<List<OfflineAsset>>(emptyList())
    override val failures = MutableStateFlow<Map<AssetId, AppError>>(emptyMap())

    override suspend fun enqueue(track: Track, candidate: PlaybackCandidate): AppResult<AssetId> =
        AppResult.Success(AssetId("download"))

    override suspend fun pause(id: AssetId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun resume(id: AssetId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun cancel(id: AssetId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun remove(id: AssetId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun removeAll(): AppResult<Unit> = AppResult.Success(Unit)
}
