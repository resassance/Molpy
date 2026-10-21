package app.musicplayer.downloads

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.*
import app.musicplayer.model.*
import app.musicplayer.network.*
import app.musicplayer.playback.*
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call

/** One application-owned transfer manager/cache/database. The service borrows this owner. */
@UnstableApi
internal class DownloadStore(
    context: Context,
    internal val repository: OfflineRepository,
    internal val resolveCandidate:
        suspend (PlaybackCandidate, NetworkContext) -> AppResult<ResolvedPlayback>,
    internal val currentNetwork: () -> NetworkContext,
    callFactoryFor: (Long) -> Call.Factory,
    private val onAssetsChanged: () -> Unit = {},
) {
    internal val context = context.applicationContext
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val mutex = Mutex()
    private val executor = Executors.newSingleThreadExecutor()
    val database = StandaloneDatabaseProvider(this.context)
    val cache =
        SimpleCache(
            File(this.context.noBackupFilesDir, "downloads/media"),
            NoOpCacheEvictor(),
            database,
        )
    private val leaseCache = DownloadLeaseCache()
    internal val recovery =
        DownloadRecovery(cache) { asset, attempt, restart, error ->
            leaseCache.invalidate(asset.id)
            post(asset.id) { rejectAttempt(asset.id, attempt, restart, error) }
        }

    private data class Removal(
        val token: String,
        val delete: Boolean,
        val retry: Boolean,
        val restartCount: Int = 1,
    )

    private val removalIntents = mutableMapOf<AssetId, Removal>()
    val manager =
        DownloadManager(
                this.context,
                database,
                cache,
                DownloadDataSource.Factory(
                    repository,
                    { asset -> resolveAssetLease(asset) },
                    callFactoryFor,
                    recovery = recovery,
                ),
                executor,
            )
            .apply { maxParallelDownloads = 1 }
    private val mutableStates = MutableStateFlow<List<OfflineAsset>>(emptyList())
    private val failureState = DownloadFailureState()
    val states = mutableStates.asStateFlow()
    val failures = failureState.values
    internal val pendingAdds = mutableSetOf<AssetId>()
    internal val removing = mutableSetOf<AssetId>()
    internal val networkPending =
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<AssetId, Boolean>()
        )
    private val serviceLifetime = DownloadServiceLifetime()

    fun serviceStarted() {
        if (serviceLifetime.serviceStarted())
            scope.launch(Dispatchers.IO) {
                networkActivated()
            }
    }

    fun serviceStopped() {
        serviceLifetime.serviceStopped()
        pauseTransfers()
    }

    fun userStarted() {
        serviceLifetime.userStarted()
        if (serviceLifetime.canResume)
            scope.launch(Dispatchers.IO) {
                networkActivated()
            }
    }

    fun serviceTimedOut() {
        serviceLifetime.timedOut()
        manager.pauseDownloads()
    }

    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val initialized = CompletableDeferred<Unit>()
    private val listener =
        object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                initialized.complete(Unit)
            }

            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                val error = finalException?.let { downloadError(it, "download.transfer") }
                post(AssetId(download.request.id)) { project(download, error) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                val id = AssetId(download.request.id)
                post(id) {
                    val asset = repository.findAsset(id) ?: return@post
                    val intent = removalIntents[id]
                    if (
                        intent != null &&
                            intent.token != download.request.data.toString(Charsets.UTF_8)
                    )
                        return@post
                    // A callback is a join boundary, never proof index/cache removal succeeded.
                    val active =
                        withContext(Dispatchers.Main.immediate) {
                            manager.currentDownloads.any { it.request.id == id.value }
                        }
                    if (manager.downloadIndex.getDownload(id.value) != null || active) {
                        fail(
                            id,
                            AppError(ErrorCode.INCOMPLETE_MEDIA, "download.remove.index", true),
                        )
                        return@post
                    }
                    finishRemoval(
                        asset,
                        intent?.delete ?: false,
                        intent?.retry ?: false,
                        intent?.restartCount ?: 1,
                    )
                }
            }
        }
    val startup: Deferred<Unit>

    init {
        manager.addListener(listener)
        if (manager.isInitialized) initialized.complete(Unit)
        startup =
            scope.async(Dispatchers.IO) {
                initialized.await()
                mutex.withLock {
                    for (asset in
                        repository.listAssets().filter { it.state == DownloadState.AVAILABLE }) {
                        repository.saveMapping(asset.copy(state = DownloadState.FAILED))
                        fail(
                            asset.id,
                            AppError(ErrorCode.INCOMPLETE_MEDIA, "download.restore", false),
                        )
                    }
                    cache.checkInitialization()
                    val indexed = mutableMapOf<String, Download>()
                    manager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) indexed[cursor.download.request.id] =
                            cursor.download
                    }
                    for (asset in repository.listAssets()) {
                        val transfer = indexed[asset.id.value]
                        when {
                            transfer != null -> {
                                recovery.restore(
                                    asset,
                                    transfer.request.data.toString(Charsets.UTF_8),
                                )
                                if (
                                    transfer.state == Download.STATE_REMOVING ||
                                        transfer.state == Download.STATE_RESTARTING
                                ) {
                                    removing += asset.id
                                    removalIntents[asset.id] =
                                        Removal(
                                            transfer.request.data.toString(Charsets.UTF_8),
                                            false,
                                            false,
                                        )
                                } else project(transfer, null)
                            }
                            else -> {
                                repository.saveMapping(asset.copy(state = DownloadState.FAILED))
                                fail(
                                    asset.id,
                                    AppError(ErrorCode.INCOMPLETE_MEDIA, "download.restore", false),
                                )
                            }
                        }
                    }
                    // Orphaned index entries cannot resolve without immutable recording identity.
                    indexed.values
                        .filter { repository.findAsset(AssetId(it.request.id)) == null }
                        .forEach {
                            withContext(Dispatchers.Main) { manager.removeDownload(it.request.id) }
                        }
                    refresh()
                    assetsChanged()
                }
            }
        scope.launch(Dispatchers.IO) {
            try {
                startup.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            for (event in events) mutex.withLock {
                try {
                    event()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    /* A failed durable projection never publishes availability. */
                }
            }
        }
    }

    fun progress(downloads: List<Download>) {
        downloads
            .filter { it.state == Download.STATE_DOWNLOADING }
            .forEach { download -> post(AssetId(download.request.id)) { project(download, null) } }
    }

    private fun post(id: AssetId, action: suspend () -> Unit) {
        events.trySend {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(id, downloadError(failure, "download.project", ErrorCode.DATABASE))
                mutableStates.update { assets ->
                    assets.map { if (it.id == id) it.copy(state = DownloadState.FAILED) else it }
                }
            }
        }
    }

    /**
     * Called under mutex, strictly after matching removal or proven index absence with no pending
     * add.
     */
    internal suspend fun finishRemoval(
        asset: OfflineAsset,
        delete: Boolean = true,
        retry: Boolean = false,
        restartCount: Int = 1,
    ) {
        check(manager.downloadIndex.getDownload(asset.id.value) == null)
        leaseCache.invalidate(asset.id)
        recovery.clean(asset)
        recovery.reset(asset)
        pendingAdds -= asset.id
        removing -= asset.id
        removalIntents.remove(asset.id)
        if (delete) {
            repository.removeMapping(asset.id)
            clearFailure(asset.id)
            assetsChanged()
        } else if (retry) {
            restartAfterRemoval(asset, restartCount)
        } else {
            repository.saveMapping(asset.copy(bytes = 0, state = DownloadState.FAILED))
        }
        refresh()
    }

    private suspend fun restartAfterRemoval(asset: OfflineAsset, restartCount: Int) {
        suspend fun failed(error: AppError) {
            repository.saveMapping(asset.copy(bytes = 0, state = DownloadState.FAILED))
            fail(asset.id, error)
        }
        val candidate = asset.trackSnapshot.candidates.single { it.id == asset.candidateId }
        val resolved = resolveCandidate(candidate, currentNetwork())
        if (resolved is AppResult.Failure) {
            failed(resolved.error)
            return
        }
        val lease = (resolved as AppResult.Success).value
        DownloadCommandPolicy.admit(lease, candidate, currentNetwork().generation)?.let {
            failed(it)
            return
        }
        val fresh =
            asset.copy(
                representation = lease.representation,
                storageKey =
                    DownloadPolicy()
                        .keyFor(asset.trackId, asset.candidateId, lease.representation.id),
                bytes = 0,
                state = DownloadState.QUEUED,
            )
        if (
            repository.listAssets().any { it.id != asset.id && it.storageKey == fresh.storageKey }
        ) {
            failed(AppError(ErrorCode.RESOURCE_CHANGED, "download.restart.identity", false))
            return
        }
        repository.saveMapping(fresh)
        recovery.restart(fresh, restartCount)
        rememberLease(fresh, lease)
        clearFailure(fresh.id)
        withContext(Dispatchers.Main.immediate) {
            if (restartCount == 0) {
                pendingAdds += fresh.id
                dispatchServiceCommand(DownloadServiceCommand.ADD, request(fresh))
                userStarted()
            } else manager.addDownload(request(fresh))
        }
    }

    internal suspend fun removeLocked(
        asset: OfflineAsset,
        delete: Boolean = true,
        retry: Boolean = false,
        restartCount: Int = 1,
    ) {
        leaseCache.invalidate(asset.id)
        networkPending -= asset.id
        repository.saveMapping(asset.copy(state = DownloadState.FAILED))
        refresh()
        val indexed = manager.downloadIndex.getDownload(asset.id.value)
        val active =
            withContext(Dispatchers.Main.immediate) {
                manager.currentDownloads.singleOrNull { it.request.id == asset.id.value }
            }
        // Index persistence can fail while the manager still owns a writer. Consult its live view
        // too.
        if (indexed == null && active == null && asset.id !in pendingAdds) {
            finishRemoval(asset, delete, retry, restartCount)
            return
        }
        removing += asset.id
        val token =
            (indexed ?: active)?.request?.data?.toString(Charsets.UTF_8)
                ?: recovery.attempt(asset).token
        removalIntents[asset.id] = Removal(token, delete, retry, restartCount)
        withContext(Dispatchers.Main.immediate) { manager.removeDownload(asset.id.value) }
        // Timeout fails closed. It never deletes or re-adds while a writer might still be active.
        scope.launch(Dispatchers.IO) {
            delay(30_000)
            post(asset.id) {
                if (removalIntents[asset.id]?.token == token) {
                    repository.findAsset(asset.id)?.let {
                        repository.saveMapping(it.copy(state = DownloadState.FAILED))
                    }
                    fail(
                        asset.id,
                        AppError(ErrorCode.INCOMPLETE_MEDIA, "download.remove.timeout", true),
                    )
                    refresh()
                }
            }
        }
    }

    private suspend fun rejectAttempt(
        id: AssetId,
        attempt: DownloadAttempt,
        restart: Boolean,
        error: AppError,
    ) {
        val asset = repository.findAsset(id) ?: return
        val indexed =
            manager.downloadIndex.getDownload(id.value)
                ?: withContext(Dispatchers.Main.immediate) {
                    manager.currentDownloads.singleOrNull { it.request.id == id.value }
                }
        if (indexed == null) {
            fail(id, error)
            repository.saveMapping(asset.copy(state = DownloadState.FAILED))
            refresh()
            return
        }
        if (indexed.request.data.toString(Charsets.UTF_8) != attempt.token || id in removing) return
        val retry = restart && attempt.claimAutomaticRestart()
        fail(id, error)
        repository.saveMapping(asset.copy(state = DownloadState.FAILED))
        refresh()
        removeLocked(asset, delete = false, retry = retry)
    }

    private fun assetsChanged() {
        try {
            onAssetsChanged()
        } catch (_: Exception) {
            /* Signal cannot demote durable audio. */
        }
    }

    internal suspend fun refresh() {
        mutableStates.value = repository.listAssets()
    }

    internal fun fail(id: AssetId, error: AppError) {
        failureState.record(id, error)
    }

    internal fun clearFailure(id: AssetId) {
        failureState.clear(id)
    }

    /** Called under mutex; the completion callback is the only path to AVAILABLE. */
    internal suspend fun project(download: Download, error: AppError?): AppResult<Unit> {
        val id = AssetId(download.request.id)
        if (id in removing) return AppResult.Success(Unit)
        val asset = repository.findAsset(id) ?: return AppResult.Success(Unit)
        val token = download.request.data.toString(Charsets.UTF_8)
        val indexed = manager.downloadIndex.getDownload(id.value) ?: return AppResult.Success(Unit)
        // Ignore delayed completion/progress from previous attempts or an older state of this
        // attempt.
        if (
            indexed.request.data.toString(Charsets.UTF_8) != token ||
                indexed.state != download.state ||
                !recovery.current(asset, token)
        )
            return AppResult.Success(Unit)
        pendingAdds -= id
        if (
            download.request.uri.toString() != StableCacheIdentity.uri(id) ||
                download.request.customCacheKey != asset.storageKey
        ) {
            val failure = AppError(ErrorCode.RESOURCE_CHANGED, "download.index", false)
            repository.saveMapping(asset.copy(state = DownloadState.FAILED))
            fail(id, failure)
            refresh()
            return AppResult.Failure(failure)
        }
        var state = DownloadProjection.state(download.state)
        var failure = error
        if (download.state == Download.STATE_COMPLETED) {
            val stamp = ResourceStampMetadata.read(cache, asset.storageKey)
            if (
                stamp == null ||
                    stamp.source != asset.source ||
                    stamp.representationId != asset.representation.id
            ) {
                val invalid = AppError(ErrorCode.RESOURCE_CHANGED, "download.stamp", false)
                recovery.reject(asset, recovery.attempt(asset), true, invalid)
                repository.saveMapping(asset.copy(state = DownloadState.FAILED))
                fail(id, invalid)
                refresh()
                return AppResult.Failure(invalid)
            }
            // mutex excludes all owner adds/removals until verification AND durable publication
            // finish.
            val verified =
                DownloadVerifier(cache) { requested ->
                        val current = manager.downloadIndex.getDownload(id.value)
                        requested == id &&
                            current?.state == Download.STATE_COMPLETED &&
                            current.request.data.toString(Charsets.UTF_8) == token &&
                            recovery.current(asset, token)
                    }
                    .verify(asset)
            when (verified) {
                is AppResult.Success -> {
                    state = DownloadState.AVAILABLE
                    clearFailure(id)
                }
                is AppResult.Failure -> {
                    state = DownloadState.FAILED
                    failure = verified.error
                }
            }
        }
        if (state == DownloadState.FAILED)
            fail(id, failure ?: AppError(ErrorCode.UNKNOWN, "download.transfer", true))
        repository.saveMapping(
            asset.copy(bytes = download.bytesDownloaded.coerceAtLeast(0), state = state)
        )
        refresh()
        if (state == DownloadState.AVAILABLE) assetsChanged()
        return if (state == DownloadState.FAILED) AppResult.Failure(failures.value.getValue(id))
        else AppResult.Success(Unit)
    }

    internal fun rememberLease(asset: OfflineAsset, lease: ResolvedPlayback) {
        val attempt = recovery.attempt(asset)
        if (attempt.rejection != null) return
        leaseCache.activate(asset.id, attempt.token)
        leaseCache.remember(asset, attempt.token, lease)
    }

    private suspend fun resolveAssetLease(asset: OfflineAsset): AppResult<ResolvedPlayback> {
        val attempt = recovery.attempt(asset)
        attempt.rejection?.let {
            return AppResult.Failure(it)
        }
        val route = currentNetwork()
        leaseCache.activate(asset.id, attempt.token)
        return leaseCache.resolve(asset, attempt.token, route.generation) {
            val candidate = asset.trackSnapshot.candidates.single { it.id == asset.candidateId }
            resolveCandidate(candidate, route)
        }
    }

    internal fun request(asset: OfflineAsset): DownloadRequest =
        DownloadRequest.Builder(asset.id.value, Uri.parse(StableCacheIdentity.uri(asset.id)))
            .setMimeType(asset.representation.mimeType)
            .setCustomCacheKey(asset.storageKey)
            .setData(recovery.attempt(asset).token.toByteArray(Charsets.UTF_8))
            .build()

    /** Pause old generation IO; continuation must retain foreground-service ownership. */
    suspend fun networkInvalidated() =
        withContext(Dispatchers.Main.immediate) {
            leaseCache.clear()
            pauseTransfers()
        }

    private fun pauseTransfers() {
        networkPending +=
            manager.currentDownloads
                .filter {
                    it.stopReason == 0 &&
                        it.state in setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING)
                }
                .map { AssetId(it.request.id) }
        manager.pauseDownloads()
    }

    internal fun dispatchServiceCommand(
        command: DownloadServiceCommand,
        request: DownloadRequest? = null,
    ) {
        when (command) {
            DownloadServiceCommand.ADD ->
                DownloadService.sendAddDownload(
                    context,
                    MusicDownloadService::class.java,
                    checkNotNull(request),
                    true,
                )
            DownloadServiceCommand.INIT ->
                DownloadService.startForeground(context, MusicDownloadService::class.java)
        }
    }

    suspend fun networkActivated() =
        withContext(Dispatchers.IO) {
            if (!serviceLifetime.canContinue) return@withContext
            startup.await()
            mutex.withLock {
                // Cancellation may race the global pause and leave a network failure in the index.
                // Retry only work that was active; explicit per-asset pause/removal cancels this
                // intent.
                val pending = networkPending.toList()
                val retries = mutableListOf<DownloadRequest>()
                for (id in pending) {
                    val indexed = manager.downloadIndex.getDownload(id.value)
                    val asset = repository.findAsset(id)
                    if (
                        asset != null &&
                            id !in removing &&
                            indexed?.state == Download.STATE_FAILED &&
                            indexed.stopReason == 0
                    )
                        retries += request(asset)
                }
                withContext(Dispatchers.Main.immediate) {
                    continueDownloadTransfers(
                        serviceLifetime,
                        currentNetwork().ready,
                        pending.isNotEmpty(),
                        dispatch = { dispatchServiceCommand(it) },
                        resume = {
                            retries.forEach { manager.addDownload(it) }
                            networkPending.removeAll(pending.toSet())
                            manager.resumeDownloads()
                        },
                    )
                }
            }
        }
}

/** Owns a separate extractor lifetime while borrowing the graph's sole routed network boundary. */
@UnstableApi
internal class DownloadRuntime(
    context: Context,
    repository: OfflineRepository,
    private val network: AndroidNetworkController,
    onAssetsChanged: () -> Unit = {},
    private val audioMetadataRecorder: AudioMetadataRecorder? = null,
) {
    private var generation = SupervisorJob()
    private var session: ExtractionSession? = null

    private fun newSession() =
        ExtractionFactory.create(
            network.transport.http,
            AndroidTokenProvider { network.context.value },
            System::currentTimeMillis,
            { network.context.value.generation },
            reuseLeases = false,
        )

    private suspend fun resolve(
        candidate: PlaybackCandidate,
        leaseContext: NetworkContext,
    ): AppResult<ResolvedPlayback> {
        val active =
            withContext(Dispatchers.Main.immediate) {
                if (
                    !leaseContext.ready ||
                        leaseContext.generation != network.context.value.generation
                )
                    null
                else (session ?: newSession().also { session = it }) to generation
            } ?: return AppResult.Failure(AppError(ErrorCode.NETWORK, "download.resolve", true))
        val task =
            CoroutineScope(active.second + Dispatchers.IO).async {
                active.first.resolver.resolve(
                    candidate,
                    PlaybackCapabilities(setOf(TransportKind.PROGRESSIVE)),
                    leaseContext,
                )
            }
        try {
            val result = task.await()
            if (result is AppResult.Success) audioMetadataRecorder?.record(candidate, result.value)
            return result
        } finally {
            task.cancel()
        }
    }

    val store =
        DownloadStore(
            context,
            repository,
            ::resolve,
            { network.context.value },
            network.transport::callFactoryFor,
            onAssetsChanged,
        )
    val port: DownloadPort = Media3DownloadPort(store)
    private val listener =
        object : NetworkLifecycle {
            override suspend fun invalidated(context: NetworkContext) {
                store.networkInvalidated()
                val old =
                    withContext(Dispatchers.Main.immediate) {
                        generation.cancel()
                        generation = SupervisorJob()
                        session.also { session = null }
                    }
                old?.close()
            }

            override suspend fun activated(context: NetworkContext) {
                store.networkActivated()
            }
        }

    init {
        network.transport.addListener(listener)
    }
}

/** Inspect only safe structured types; never retain an upstream Throwable or parse its text. */
internal fun downloadError(
    failure: Throwable,
    operation: String,
    fallback: ErrorCode = ErrorCode.NETWORK,
): AppError {
    var current: Throwable? = failure
    repeat(12) {
        when (val cause = current) {
            is DownloadFailure -> return cause.error
            is AppFailureException -> return cause.error
            is SQLiteFullException -> return AppError(ErrorCode.STORAGE_FULL, operation, true)
            is ErrnoException ->
                if (cause.errno == OsConstants.ENOSPC)
                    return AppError(ErrorCode.STORAGE_FULL, operation, true)
        }
        current = current?.cause
    }
    return AppError(fallback, operation, true)
}

/** Attachment owns IO admission; ordinary detach preserves user intent, never an OS timeout. */
internal class DownloadServiceLifetime {
    @Volatile private var attached = false
    @Volatile private var continuationRequested = false
    @Volatile private var stoppedByOs = false
    val canContinue
        get() = continuationRequested && !stoppedByOs

    val canResume
        get() = attached && canContinue

    fun serviceStarted(): Boolean {
        attached = true
        return canResume
    }

    fun serviceStopped() {
        attached = false
    }

    fun resumeAfterAttachment(start: () -> Unit): Boolean {
        if (canResume) return true
        if (canContinue)
            try {
                start()
            } catch (_: IllegalStateException) {
                /* Foreground start rejected. */
            } catch (_: SecurityException) {
                /* Missing platform permission does not authorize IO. */
            }
        return false
    }

    fun userStarted() {
        continuationRequested = true
        stoppedByOs = false
    }

    fun timedOut() {
        stoppedByOs = true
    }
}

/** Called on Main under the owner mutex; re-evaluate current admission before draining retries. */
@UnstableApi
internal fun continueDownloadTransfers(
    lifetime: DownloadServiceLifetime,
    networkReady: Boolean,
    hasPending: Boolean,
    dispatch: (DownloadServiceCommand) -> Unit,
    resume: () -> Unit,
) {
    if (!networkReady || (!lifetime.canResume && !hasPending)) return
    if (!lifetime.resumeAfterAttachment { dispatch(DownloadServiceCommand.INIT) }) return
    resume()
}
