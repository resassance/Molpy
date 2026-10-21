package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import app.musicplayer.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Explicit commands only. Media3 owns all actual scheduling, progress and transfer persistence. */
@UnstableApi
internal class Media3DownloadPort(private val store: DownloadStore) : DownloadPort {
    override val states = store.states
    override val failures = store.failures

    override suspend fun enqueue(track: Track, candidate: PlaybackCandidate): AppResult<AssetId> =
        command {
            if (track.candidates.singleOrNull { it.id == candidate.id } != candidate)
                return@command failure(ErrorCode.INVALID_CONFIG)
            val network = store.currentNetwork()
            if (!network.ready || !network.online) return@command failure(ErrorCode.NETWORK, true)
            val lease =
                when (val result = store.resolveCandidate(candidate, network)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> return@command result
                }
            DownloadCommandPolicy.admit(lease, candidate, store.currentNetwork().generation)?.let {
                return@command AppResult.Failure(it)
            }
            val key = DownloadPolicy().keyFor(track.id, candidate.id, lease.representation.id)
            val previous = store.repository.listAssets().singleOrNull { it.storageKey == key }
            if (
                previous != null &&
                    representationCompatibility(
                        previous.source,
                        previous.representation,
                        candidate.source,
                        lease.representation,
                    ) != RepresentationCompatibility.COMPATIBLE
            )
                return@command failure(ErrorCode.RESOURCE_CHANGED)
            val asset =
                previous
                    ?: OfflineAsset(
                        AssetId(UUID.randomUUID().toString()),
                        track.id,
                        track,
                        candidate.id,
                        candidate.source,
                        lease.representation,
                        key,
                        0,
                        DownloadState.QUEUED,
                    )
            if (asset.id in store.removing) return@command failure(ErrorCode.INVALID_CONFIG, true)
            if (previous?.state == DownloadState.AVAILABLE)
                return@command AppResult.Success(asset.id)
            if (previous?.state == DownloadState.FAILED) {
                store.removeLocked(asset, delete = false, retry = true, restartCount = 0)
                return@command AppResult.Success(asset.id)
            }
            store.repository.saveMapping(asset.copy(state = DownloadState.QUEUED))
            store.rememberLease(asset, lease)
            start(asset)
            store.refresh()
            AppResult.Success(asset.id)
        }

    override suspend fun pause(id: AssetId): AppResult<Unit> =
        command(id) {
            val asset =
                store.repository.findAsset(id) ?: return@command failure(ErrorCode.INCOMPLETE_MEDIA)
            store.networkPending -= id
            if (asset.state == DownloadState.AVAILABLE) return@command AppResult.Success(Unit)
            withContext(Dispatchers.Main) { store.manager.setStopReason(id.value, USER_PAUSED) }
            store.repository.saveMapping(asset.copy(state = DownloadState.PAUSED))
            store.refresh()
            AppResult.Success(Unit)
        }

    override suspend fun resume(id: AssetId): AppResult<Unit> =
        command(id) {
            val asset =
                store.repository.findAsset(id) ?: return@command failure(ErrorCode.INCOMPLETE_MEDIA)
            if (id in store.removing) return@command failure(ErrorCode.INVALID_CONFIG, true)
            val indexed = store.manager.downloadIndex.getDownload(id.value)
            if (indexed?.state == Download.STATE_COMPLETED && asset.state != DownloadState.FAILED)
                return@command store.project(indexed, null)
            if (!store.currentNetwork().ready) return@command failure(ErrorCode.NETWORK, true)
            if (asset.state == DownloadState.FAILED) {
                store.removeLocked(asset, delete = false, retry = true, restartCount = 0)
                return@command AppResult.Success(Unit)
            }
            start(asset)
            store.repository.saveMapping(asset.copy(state = DownloadState.QUEUED))
            store.refresh()
            AppResult.Success(Unit)
        }

    override suspend fun cancel(id: AssetId): AppResult<Unit> = remove(id)

    override suspend fun remove(id: AssetId): AppResult<Unit> =
        command(id) {
            removeLocked(id)
            AppResult.Success(Unit)
        }

    override suspend fun removeAll(): AppResult<Unit> = command {
        store.repository.listAssets().forEach { removeLocked(it.id) }
        AppResult.Success(Unit)
    }

    private suspend fun removeLocked(id: AssetId) {
        val asset = store.repository.findAsset(id) ?: return
        store.removeLocked(asset)
    }

    private suspend fun start(asset: OfflineAsset) {
        val pendingAdd = asset.id in store.pendingAdds
        val indexed =
            if (pendingAdd) null else store.manager.downloadIndex.getDownload(asset.id.value)
        var submittedAdd = false
        try {
            withContext(Dispatchers.Main) {
                dispatchExplicitDownloadStart(
                    pendingAdd,
                    indexed?.state,
                    indexed?.stopReason,
                    dispatch = { command ->
                        if (command == DownloadServiceCommand.ADD) {
                            store.pendingAdds += asset.id
                            submittedAdd = true
                            store.dispatchServiceCommand(command, store.request(asset))
                        } else store.dispatchServiceCommand(command)
                    },
                    userStarted = store::userStarted,
                )
            }
        } catch (cancelled: CancellationException) {
            if (submittedAdd) store.pendingAdds -= asset.id
            throw cancelled
        } catch (failure: Exception) {
            if (submittedAdd) store.pendingAdds -= asset.id
            val error = downloadError(failure, "download.start")
            store.repository.saveMapping(asset.copy(state = DownloadState.FAILED))
            store.fail(asset.id, error)
            store.refresh()
            throw DownloadFailure(error)
        }
    }

    private suspend fun <T> command(
        id: AssetId? = null,
        action: suspend () -> AppResult<T>,
    ): AppResult<T> =
        withContext(Dispatchers.IO) {
            try {
                store.startup.await()
                store.mutex.withLock { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val error = downloadError(failure, "download.command")
                if (id != null) store.fail(id, error)
                AppResult.Failure(error)
            }
        }

    private fun failure(code: ErrorCode, retryable: Boolean = false) =
        AppResult.Failure(AppError(code, "download.command", retryable))

    private companion object {
        const val USER_PAUSED = 1
    }
}

@UnstableApi
internal enum class DownloadServiceCommand(val action: String) {
    ADD(DownloadService.ACTION_ADD_DOWNLOAD),
    INIT(DownloadService.ACTION_INIT),
}

/** Shared by explicit enqueue/resume; dispatch acceptance precedes user admission. */
@UnstableApi
internal fun dispatchExplicitDownloadStart(
    pendingAdd: Boolean,
    indexedState: Int?,
    stopReason: Int?,
    dispatch: (DownloadServiceCommand) -> Unit,
    userStarted: () -> Unit,
) {
    // A native row or pending ADD does not establish global resume or foreground attachment.
    if (
        pendingAdd ||
            (indexedState in setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING) &&
                stopReason == 0)
    ) {
        dispatch(DownloadServiceCommand.INIT)
    } else {
        dispatch(DownloadServiceCommand.ADD)
    }
    userStarted()
}
