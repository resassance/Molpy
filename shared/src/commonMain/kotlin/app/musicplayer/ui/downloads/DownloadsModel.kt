package app.musicplayer.ui.downloads

import app.musicplayer.downloads.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun downloadProgress(asset: OfflineAsset): Float? =
    asset.representation.expectedBytes
        ?.takeIf { it > 0 }
        ?.let { (asset.bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }

fun downloadDurationMs(asset: OfflineAsset): Long? =
    asset.representation.expectedDurationMs?.takeIf { it > 0 }
        ?: asset.trackSnapshot.candidates
            .firstOrNull { it.id == asset.candidateId && it.source == asset.source }
            ?.durationMs
            ?.takeIf { it > 0 }
        ?: asset.trackSnapshot.durationMs?.takeIf {
            it > 0 && asset.trackSnapshot.candidates.firstOrNull()?.source == asset.source
        }

fun downloadTrack(asset: OfflineAsset): Track {
    val duration = downloadDurationMs(asset) ?: return asset.trackSnapshot
    return asset.trackSnapshot
        .copy(
            candidates =
                asset.trackSnapshot.candidates.map {
                    if (it.id == asset.candidateId && it.source == asset.source)
                        it.copy(durationMs = duration)
                    else it
                }
        )
        .withMissingDuration(asset.source, duration)
}

data class DownloadsState(
    val assets: List<OfflineAsset> = emptyList(),
    val usage: StorageUsageSnapshot = StorageUsageSnapshot(0, emptyMap()),
    val failures: Map<AssetId, AppError> = emptyMap(),
    val busy: Set<AssetId> = emptySet(),
    val loading: Boolean = false,
    val error: AppError? = null,
)

class DownloadsModel(
    private val downloads: DownloadPort,
    private val measureStorage: suspend () -> StorageUsageSnapshot,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = mutable.asStateFlow()
    private val commandFailures = mutableMapOf<AssetId, AppError>()
    private var providerFailures = emptyMap<AssetId, AppError>()
    private val versions = mutableMapOf<AssetId, Long>()
    private var measurement: Job? = null
    private var shape: List<Triple<AssetId, DownloadState, Long>>? = null

    init {
        scope.launch {
            downloads.states.collect { assets ->
                mutable.update { it.copy(assets = assets) }
                val next = assets.map { Triple(it.id, it.state, it.bytes / (1024 * 1024)) }
                if (shape != next) {
                    shape = next
                    refreshStorage()
                }
            }
        }
        scope.launch {
            downloads.failures.collect {
                providerFailures = it
                mutable.update { old -> old.copy(failures = providerFailures + commandFailures) }
            }
        }
    }

    fun refreshStorage() {
        if (measurement?.isActive == true) return
        mutable.update { it.copy(loading = true) }
        measurement = scope.launch {
            try {
                val usage = measureStorage()
                ensureActive()
                mutable.update { it.copy(usage = usage, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(error = AppError(ErrorCode.DATABASE, "downloads.storage", true))
                }
            } finally {
                mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun pause(id: AssetId) = command(id) { downloads.pause(id) }

    suspend fun resume(id: AssetId) = command(id) { downloads.resume(id) }

    suspend fun cancel(id: AssetId) = command(id) { downloads.cancel(id) }

    suspend fun remove(id: AssetId) = command(id) { downloads.remove(id) }

    private suspend fun command(
        id: AssetId,
        action: suspend () -> AppResult<Unit>,
    ): AppResult<Unit> {
        val version = (versions[id] ?: 0) + 1
        versions[id] = version
        mutable.update { it.copy(busy = it.busy + id) }
        try {
            val result =
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AppResult.Failure(AppError(ErrorCode.UNKNOWN, "downloads.command", true))
                }
            if (versions[id] == version) {
                if (result is AppResult.Failure) commandFailures[id] = result.error
                else commandFailures.remove(id)
                mutable.update { it.copy(failures = providerFailures + commandFailures) }
            }
            refreshStorage()
            return result
        } finally {
            if (versions[id] == version) mutable.update { it.copy(busy = it.busy - id) }
        }
    }

    fun close() {
        job.cancel()
    }
}
