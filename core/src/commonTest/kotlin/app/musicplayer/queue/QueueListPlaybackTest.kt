package app.musicplayer.queue

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import app.musicplayer.playback.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueListPlaybackTest {
    @Test
    fun aLateMetadataBatchCannotReplaceANewerSelection() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val gate = CompletableDeferred<Unit>()
        val commands =
            QueuePlaybackCommands(
                queue,
                { fail("Expected a batch") },
                { batch ->
                    if (batch.first().id == a.id) withContext(NonCancellable) { gate.await() }
                    batch.forEach { tracks[it.id] = it }
                    AppResult.Success(Unit)
                },
            )
        val old = launch { commands.playList(listOf(a), 0) }
        runCurrent()
        commands.playList(listOf(b), 0)
        gate.complete(Unit)
        old.join()
        runCurrent()
        assertEquals(listOf(b.id), queue.state.value.entries.map { it.trackId })
        assertEquals(b.id, engine.snapshot.value.track?.id)
    }

    @Test
    fun aPlaylistUsesOneMetadataBatchBeforeInstallingItsOccurrences() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val batches = mutableListOf<List<Track>>()
        val commands =
            QueuePlaybackCommands(
                queue,
                remember = { fail("List playback must use the batch boundary") },
                rememberList = { batch ->
                    batches += batch
                    batch.forEach { tracks[it.id] = it }
                    AppResult.Success(Unit)
                },
            )
        assertIs<AppResult.Success<Unit>>(commands.playList(listOf(a, a, b), 1))
        assertEquals(listOf(listOf(a, a, b)), batches)
        assertEquals(listOf(a.id, a.id, b.id), queue.state.value.entries.map { it.trackId })
        assertEquals(a.id, engine.snapshot.value.track?.id)
        val before = queue.state.value
        val failed =
            QueuePlaybackCommands(
                queue,
                { AppResult.Success(Unit) },
                {
                    AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture.batch", true))
                },
            )
        assertEquals(ErrorCode.DATABASE, failed.playList(listOf(b), 0).errorOrNull()?.code)
        assertEquals(before, queue.state.value)
    }

    private val a = Fixtures.track("a", sourceId = "a")
    private val b = Fixtures.track("b", sourceId = "b")
    private val network =
        NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)

    @Test
    fun replacementCreatesOccurrencesAndMoveKeepsCurrentPositionAndLoad() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val commands =
            QueuePlaybackCommands(queue) {
                tracks[it.id] = it
                AppResult.Success(Unit)
            }
        assertIs<AppResult.Success<Unit>>(commands.playList(listOf(a, a, b, b), 1))
        runCurrent()
        val entries = queue.state.value.entries
        assertNotEquals(entries[0].id, entries[1].id)
        assertEquals(entries[1].id, queue.state.value.currentEntryId)
        queue.seek(1234)
        val before = engine.loads
        commands.dispatch(QueueCommand.MoveUpcoming(entries[3].id, 0))
        assertEquals(entries[1].id, queue.state.value.currentEntryId)
        assertEquals(1234L, queue.state.value.positionMs)
        assertTrue(queue.state.value.playWhenReady)
        assertEquals(before, engine.loads)
        assertEquals(
            listOf(entries[0], entries[1], entries[3], entries[2]),
            queue.state.value.entries,
        )
    }

    @Test
    fun failedRememberAndInvalidListsLeaveExistingQueueAndAudioUntouched() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>(a.id to a)
        val queue = queue(backgroundScope, engine, tracks)
        val commands = QueuePlaybackCommands(queue) { AppResult.Success(Unit) }
        commands.playList(listOf(a), 0)
        val before = queue.state.value
        val loads = engine.loads
        val failed =
            QueuePlaybackCommands(queue) {
                AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
            }
        assertEquals(ErrorCode.DATABASE, failed.playList(listOf(b), 0).errorOrNull()?.code)
        assertEquals(
            ErrorCode.INVALID_CONFIG,
            commands.playList(emptyList(), 0).errorOrNull()?.code,
        )
        assertEquals(ErrorCode.INVALID_CONFIG, commands.playList(listOf(a), 2).errorOrNull()?.code)
        assertEquals(before, queue.state.value)
        assertEquals(loads, engine.loads)
        val duplicate = QueueEntry(QueueEntryId("duplicate"), a.id)
        assertEquals(
            ErrorCode.INVALID_CONFIG,
            queue.replace(listOf(duplicate, duplicate), duplicate.id).errorOrNull()?.code,
        )
        assertEquals(before, queue.state.value)
    }

    @Test
    fun newerSelectionSupersedesDelayedMetadataBeforeReplace() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val gate = CompletableDeferred<Unit>()
        val commands =
            QueuePlaybackCommands(queue) { track ->
                if (track.id == a.id) withContext(NonCancellable) { gate.await() }
                tracks[track.id] = track
                AppResult.Success(Unit)
            }
        val first = launch { commands.playList(listOf(a), 0) }
        runCurrent()
        commands.playList(listOf(b), 0)
        gate.complete(Unit)
        first.join()
        runCurrent()
        assertEquals(listOf(b.id), queue.state.value.entries.map { it.trackId })
        assertEquals(b.id, engine.snapshot.value.track?.id)
    }

    @Test
    fun explicitPauseInvalidationPreventsDelayedListFromPlaying() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val gate = CompletableDeferred<Unit>()
        val commands =
            QueuePlaybackCommands(queue) { track ->
                gate.await()
                tracks[track.id] = track
                AppResult.Success(Unit)
            }
        val first = launch { commands.playList(listOf(a), 0) }
        runCurrent()
        commands.invalidatePending()
        queue.pause()
        gate.complete(Unit)
        first.join()
        runCurrent()
        assertTrue(queue.state.value.entries.isEmpty())
        assertEquals(0, engine.loads)
    }

    @Test
    fun invalidationWhileWaitingForQueueLockIsCheckedBeforeReplace() = runTest {
        lockInvalidation { it.playList(listOf(b), 0) }
    }

    @Test
    fun invalidationWhileWaitingForQueueLockIsCheckedBeforeSingleSelection() = runTest {
        lockInvalidation { it.select(b, QueueEntry(QueueEntryId("new-b"), b.id), 0, true) }
    }

    private suspend fun TestScope.lockInvalidation(
        command: suspend (QueuePlaybackCommands) -> AppResult<Unit>
    ) {
        val engine = Engine()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ): AppResult<ResolvedPlayback> = error("Unexpected resolution")
            }
        val coordinator = PlaybackCoordinator(engine, resolver, { network }, backgroundScope)
        val queue =
            QueueController(engine, coordinator) { id ->
                if (id == a.id) {
                    entered.complete(Unit)
                    release.await()
                    a
                } else b
            }
        val old = QueueEntry(QueueEntryId("restored"), a.id)
        val restore = launch { queue.restore(QueueSnapshot(listOf(old), old.id)) }
        entered.await()
        val commands = QueuePlaybackCommands(queue) { AppResult.Success(Unit) }
        val pending = launch { command(commands) }
        runCurrent()
        commands.invalidatePending()
        release.complete(Unit)
        restore.join()
        pending.join()
        runCurrent()
        assertEquals(listOf(old), queue.state.value.entries)
        assertFalse(queue.state.value.playWhenReady)
        assertEquals(0, engine.loads)
    }

    @Test
    fun lateEnqueueMetadataCannotCancelNewerListPlayback() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val gate = CompletableDeferred<Unit>()
        val commands =
            QueuePlaybackCommands(queue) { track ->
                if (track.id == a.id) withContext(NonCancellable) { gate.await() }
                tracks[track.id] = track
                AppResult.Success(Unit)
            }
        val pending = launch { commands.enqueue(a) }
        runCurrent()
        commands.playList(listOf(b), 0)
        runCurrent()
        gate.complete(Unit)
        pending.join()
        runCurrent()
        assertEquals(listOf(b.id), queue.state.value.entries.map { it.trackId })
        assertEquals(b.id, engine.snapshot.value.track?.id)
    }

    @Test
    fun enqueueRemembersTrackWithoutStartingTransportAndStorageFailureIsAtomic() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val commands =
            QueuePlaybackCommands(queue) {
                tracks[it.id] = it
                AppResult.Success(Unit)
            }
        assertIs<AppResult.Success<Unit>>(commands.enqueue(a))
        assertEquals(listOf(a.id), queue.state.value.entries.map { it.trackId })
        assertEquals(a.id, queue.state.value.entries.firstOrNull()?.trackId)
        assertEquals(0, engine.loads)
        val failed =
            QueuePlaybackCommands(queue) {
                AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
            }
        assertEquals(ErrorCode.DATABASE, failed.enqueue(b).errorOrNull()?.code)
        assertEquals(listOf(a.id), queue.state.value.entries.map { it.trackId })
    }

    @Test
    fun emptyInsertionWaitsForExplicitPlayAndKeepsModeOnRestore() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val commands =
            QueuePlaybackCommands(queue) { track ->
                tracks[track.id] = track
                AppResult.Success(Unit)
            }
        commands.enqueue(a, QueueInsertion.NEXT)
        assertEquals(a.id, queue.snapshot.value.track?.id)
        assertEquals(PlayerStatus.PAUSED, queue.snapshot.value.status)
        assertEquals(0, engine.loads)
        commands.dispatch(QueueCommand.SetShuffle(true))
        val saved = queue.state.value
        queue.restore(saved)
        assertTrue(queue.state.value.shuffleEnabled)
        assertEquals(saved.traversal, queue.state.value.traversal)
        assertEquals(0, engine.loads)
        queue.play()
        assertEquals(1, engine.loads)
    }

    @Test
    fun delayedInsertionsBothSucceedAndToggleDoesNotCancelRemember() = runTest {
        val engine = Engine()
        val tracks = mutableMapOf<TrackId, Track>()
        val queue = queue(backgroundScope, engine, tracks)
        val gate = CompletableDeferred<Unit>()
        val commands =
            QueuePlaybackCommands(queue) { track ->
                if (track.id == b.id) gate.await()
                tracks[track.id] = track
                AppResult.Success(Unit)
            }
        commands.playList(listOf(a), 0)
        val before = engine.snapshot.value
        val first = launch { commands.enqueue(b, QueueInsertion.NEXT) }
        runCurrent()
        val c = Fixtures.track("c", sourceId = "c")
        val second = launch { commands.enqueue(c, QueueInsertion.NEXT) }
        runCurrent()
        commands.dispatch(QueueCommand.ToggleShuffle)
        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(c.id, b.id), QueueOrder.upcoming(queue.state.value).map { it.trackId })
        assertEquals(1, engine.loads)
        assertEquals(before, engine.snapshot.value)
    }

    private fun queue(
        scope: CoroutineScope,
        engine: Engine,
        tracks: Map<TrackId, Track>,
    ): QueueController {
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ) =
                    AppResult.Success(
                        ResolvedPlayback(
                            candidate.id,
                            Representation("f", "audio/test", "test", null, 10000, null),
                            TransportKind.PROGRESSIVE,
                            MediaRequest("https://example.invalid/audio", emptyMap()),
                            null,
                            0,
                        )
                    )
            }
        val coordinator = PlaybackCoordinator(engine, resolver, { network }, scope)
        return QueueController(engine, coordinator) { tracks[it] }
            .also { queue ->
                scope.launch { coordinator.snapshot.collect { queue.onPlayback(it) } }
            }
    }

    private class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var loads = 0

        override suspend fun load(
            entry: QueueEntry,
            source: PlayableSource,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> {
            loads++
            snapshot.value =
                PlaybackSnapshot(
                    if (playWhenReady) PlayerStatus.PLAYING else PlayerStatus.PAUSED,
                    entry,
                    source.track,
                    positionMs,
                    10000,
                    playWhenReady,
                    loadId =
                        when (source) {
                            is PlayableSource.Local -> source.loadId
                            is PlayableSource.Remote -> source.loadId
                        },
                )
            return AppResult.Success(Unit)
        }

        override fun play() {
            snapshot.value =
                snapshot.value.copy(status = PlayerStatus.PLAYING, playWhenReady = true)
        }

        override fun pause() {
            snapshot.value =
                snapshot.value.copy(status = PlayerStatus.PAUSED, playWhenReady = false)
        }

        override fun seek(positionMs: Long) {
            snapshot.value = snapshot.value.copy(positionMs = positionMs)
        }

        override fun stop() {
            snapshot.value = PlaybackSnapshot()
        }

        override fun release() = stop()
    }
}
