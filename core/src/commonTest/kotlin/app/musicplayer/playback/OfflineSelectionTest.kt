package app.musicplayer.playback

import app.musicplayer.downloads.*
import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class OfflineSelectionTest {
    private val saved = Fixtures.track("saved").copy(title = "Saved recording")
    private val asset = OfflineAsset(AssetId("asset"), saved.id, saved, saved.candidates.first().id,
        saved.candidates.first().source, Representation("wav", "audio/wav", "pcm", 100, 1000, null), "cache", 100, DownloadState.AVAILABLE)
    private class Repository(var asset: OfflineAsset?) : OfflineRepository {
        override suspend fun findAvailable(trackId: TrackId) = asset?.takeIf { it.trackId == trackId && it.state == DownloadState.AVAILABLE }
        override suspend fun findAsset(id: AssetId) = asset
        override suspend fun listAssets() = listOfNotNull(asset)
        override suspend fun saveMapping(asset: OfflineAsset) { this.asset = asset }
        override suspend fun removeMapping(id: AssetId) { asset = null }
        override suspend fun reconcile(existingStorageKeys: Set<String>) {}
    }
    @Test fun readyLocalNeedsNoNetworkAndKeepsRecordingSnapshot() = runTest {
        var calls = 0
        val resolver = object : PlaybackResolver {
            override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> { calls++; error("local must not resolve") }
        }
        val selector = PlaybackSourceSelector(resolver, { NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), false, false) },
            Repository(asset), { AppResult.Success(PlayableSource.Local(it.id, "local:asset", it.representation.mimeType, it.storageKey, it.trackSnapshot)) })
        val source = assertIs<PlayableSource.Local>(selector.select(saved.copy(title = "Updated catalog")).valueOrNull())
        assertEquals(saved, source.track)
        assertEquals(0, calls)
    }
    @Test fun missingLocalFailsClosedAndRevokesAvailability() = runTest {
        val repository = Repository(asset)
        var calls = 0
        val resolver = object : PlaybackResolver {
            override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> { calls++; error("hole must not resolve") }
        }
        val selector = PlaybackSourceSelector(resolver, { NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true) }, repository,
            { AppResult.Failure(AppError(ErrorCode.INCOMPLETE_MEDIA, "local.read", false)) })
        assertEquals(ErrorCode.INCOMPLETE_MEDIA, selector.select(saved).errorOrNull()?.code)
        assertEquals(DownloadState.FAILED, repository.asset?.state)
        assertEquals(0, calls)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun localLoadSurvivesNetworkChangesAndRejectsOldSameEntryCallbacks() = runTest {
        var calls = 0
        val resolver = object : PlaybackResolver {
            override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> { calls++; error("local must not resolve") }
        }
        var route = NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
        val repository = Repository(asset)
        val selector = PlaybackSourceSelector(resolver, { route }, repository,
            { AppResult.Success(PlayableSource.Local(it.id, "local:asset", it.representation.mimeType, it.storageKey, it.trackSnapshot)) })
        var stops = 0
        val engine = object : PlayerEngine {
            override val snapshot = MutableStateFlow(PlaybackSnapshot())
            override suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
                snapshot.value = PlaybackSnapshot(PlayerStatus.PLAYING, entry, source.track, positionMs, 1000, playWhenReady,
                    loadId = (source as PlayableSource.Local).loadId)
                return AppResult.Success(Unit)
            }
            override fun play() {}
            override fun pause() {}
            override fun seek(positionMs: Long) {}
            override fun stop() { stops++ }
            override fun release() {}
        }
        val coordinator = PlaybackCoordinator(engine, resolver, { route }, backgroundScope, sourceSelector = selector)
        val entry = QueueEntry(QueueEntryId("same"), saved.id)
        coordinator.select(saved.copy(title = "New catalog title"), entry, 100, true)
        runCurrent()
        val oldLoad = engine.snapshot.value
        coordinator.select(saved, entry, 400, true)
        runCurrent()
        val currentLoad = engine.snapshot.value
        val stopsAfterLoad = stops
        route = route.copy(generation = 1, ready = false, online = false)
        coordinator.networkInvalidated()
        coordinator.networkActivated()
        assertEquals(stopsAfterLoad, stops)
        assertEquals(saved, coordinator.snapshot.value.track)
        assertEquals(PlayerStatus.PLAYING, coordinator.snapshot.value.status)
        engine.snapshot.value = oldLoad.copy(status = PlayerStatus.FAILED, error = AppError(ErrorCode.URL_REJECTED, "stale", true))
        runCurrent()
        assertEquals(400, coordinator.snapshot.value.positionMs)
        assertEquals(DownloadState.AVAILABLE, repository.asset?.state)
        engine.snapshot.value = currentLoad.copy(status = PlayerStatus.FAILED, error = AppError(ErrorCode.URL_REJECTED, "hole", true))
        runCurrent()
        assertEquals(DownloadState.FAILED, repository.asset?.state)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        assertEquals(0, calls)
    }

    @Test fun brokenLocalReadinessCannotTurnIntoRemoteRecovery() = runTest {
        var remoteCalls = 0
        val resolver = object : PlaybackResolver {
            override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> {
                remoteCalls++
                return AppResult.Failure(AppError(ErrorCode.NETWORK, "unexpected.remote", true))
            }
        }
        val route = NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
        val selector = PlaybackSourceSelector(resolver, { route }, Repository(asset),
            { AppResult.Failure(AppError(ErrorCode.INCOMPATIBLE_RESOURCE, "cache.identity", true)) })
        val engine = object : PlayerEngine {
            override val snapshot = MutableStateFlow(PlaybackSnapshot())
            override suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> = error("No readable local source")
            override fun play() {}
            override fun pause() {}
            override fun seek(positionMs: Long) {}
            override fun stop() {}
            override fun release() {}
        }
        val coordinator = PlaybackCoordinator(engine, resolver, { route }, backgroundScope, sourceSelector = selector)
        val result = coordinator.select(saved, QueueEntry(QueueEntryId("broken"), saved.id), 0, true)
        assertEquals(0, remoteCalls, "Broken local asset must not enter remote recovery")
        assertEquals(ErrorCode.INCOMPLETE_MEDIA, result.errorOrNull()?.code)
    }

    @Test fun synchronousLocalLoadFailureCannotSpendRemoteRecovery() = runTest {
        var remoteCalls = 0
        val resolver = object : PlaybackResolver {
            override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> { remoteCalls++; error("No local resolve") }
        }
        val route = NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
        val repository = Repository(asset)
        val selector = PlaybackSourceSelector(resolver, { route }, repository,
            { AppResult.Success(PlayableSource.Local(it.id, "local:asset", it.representation.mimeType, it.storageKey, it.trackSnapshot)) })
        val engine = object : PlayerEngine {
            override val snapshot = MutableStateFlow(PlaybackSnapshot())
            override suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> =
                AppResult.Failure(AppError(ErrorCode.URL_REJECTED, "local.engine", true))
            override fun play() {}
            override fun pause() {}
            override fun seek(positionMs: Long) {}
            override fun stop() {}
            override fun release() {}
        }
        val coordinator = PlaybackCoordinator(engine, resolver, { route }, backgroundScope, sourceSelector = selector)
        assertIs<AppResult.Failure>(coordinator.select(saved.copy(title = "Updated metadata"), QueueEntry(QueueEntryId("local-failure"), saved.id), 0, true))
        assertEquals(saved, coordinator.snapshot.value.track)
        assertEquals(DownloadState.FAILED, repository.asset?.state)
        assertEquals(0, remoteCalls)
    }

    @Test fun unreadyOrOfflineRouteDoesNotResolve() = runTest {
        var calls = 0
        val resolver = object : PlaybackResolver {
            override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> { calls++; error("route is unavailable") }
        }
        for ((ready, online) in listOf(false to true, true to false)) {
            val selector = PlaybackSourceSelector(resolver, { NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), ready, online) })
            assertEquals(ErrorCode.NETWORK, selector.select(saved).errorOrNull()?.code)
        }
        assertEquals(0, calls)
    }
}
