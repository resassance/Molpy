package app.musicplayer.queue

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QueueSourceControllerTest {
    private fun identity(n: Int, id: String) =
        PlaylistRowIdentity(n, "row-$n", SourceRef("youtube", "video", id))

    private suspend fun PlaylistQueueFixture.start() =
        queue.dispatch(
            QueueCommand.Replace(
                listOf(entry("a")),
                QueueEntryId("a"),
                QueueSourceState(
                    "s",
                    PublicPlaylistRef("Mix", SourceRef("youtube", "playlist", "PLtest")),
                    0,
                    listOf(identity(0, "a")),
                ),
            )
        )

    @Test
    fun pageAndPendingShuffleDoNotReloadCurrentAudio() = runTest {
        val f = PlaylistQueueFixture(backgroundScope)
        f.start()
        runCurrent()
        f.queue.seek(1234)
        runCurrent()
        val old = f.queue.snapshot.value
        f.queue.dispatch(QueueCommand.ToggleShuffle)
        f.queue.dispatch(
            QueueCommand.AppendSourcePage(
                "s",
                1,
                listOf(PlaylistQueueRow(identity(1, "b"), f.entry("b"))),
                false,
            )
        )
        runCurrent()
        assertEquals(1, f.engine.loads)
        assertEquals(1, f.resolves)
        assertEquals(old.loadId, f.queue.snapshot.value.loadId)
        assertEquals(1234L, f.queue.snapshot.value.positionMs)
        assertEquals(old.bufferedPositionMs, f.queue.snapshot.value.bufferedPositionMs)
    }

    @Test
    fun naturalEndKeepsPlayIntentButPauseWhileWaitingPreventsSound() = runTest {
        val f = PlaylistQueueFixture(backgroundScope)
        f.start()
        runCurrent()
        f.engine.snapshot.value =
            f.engine.snapshot.value.copy(status = PlayerStatus.ENDED, playWhenReady = false)
        runCurrent()
        assertTrue(f.queue.state.value.source!!.waiting)
        assertTrue(f.queue.state.value.playWhenReady)
        assertEquals(PlayerStatus.BUFFERING, f.queue.snapshot.value.status)
        f.queue.pause()
        runCurrent()
        f.queue.dispatch(
            QueueCommand.AppendSourcePage(
                "s",
                1,
                listOf(PlaylistQueueRow(identity(1, "b"), f.entry("b"))),
                true,
            )
        )
        runCurrent()
        assertEquals(1, f.engine.loads)
        assertFalse(f.queue.state.value.playWhenReady)
        f.queue.play()
        runCurrent()
        assertEquals("b", f.queue.state.value.currentEntryId!!.value)
        assertEquals(2, f.engine.loads)
        assertFalse(f.queue.state.value.source!!.waiting)
        assertEquals(PlayerStatus.PLAYING, f.queue.snapshot.value.status)
    }

    @Test
    fun restoreAndEveryMutationHaveIncreasingRevisions() = runTest {
        val f = PlaylistQueueFixture(backgroundScope)
        f.start()
        runCurrent()
        val a = f.queue.state.value.revision
        f.queue.pause()
        val b = f.queue.state.value.revision
        f.queue.seek(10)
        val c = f.queue.state.value.revision
        val saved = f.queue.state.value
        f.queue.restore(saved)
        assertTrue(a > 0 && b > a && c > b && f.queue.state.value.revision > c)
        assertFalse(f.queue.state.value.playWhenReady)
    }

    @Test
    fun restoreKeepsNullCurrentAtUnknownBoundary() = runTest {
        val f = PlaylistQueueFixture(backgroundScope)
        val tail = f.entry("tail")
        val source =
            QueueSourceState(
                "s",
                PublicPlaylistRef("Mix", SourceRef("youtube", "playlist", "PLtest")),
                0,
                listOf(identity(0, "a")),
                deferredEndIds = listOf(tail.id),
                waiting = true,
            )
        f.queue.restore(
            QueueSnapshot(
                listOf(tail),
                null,
                traversal = QueueTraversal(upcoming = listOf(tail.id)),
                source = source,
            )
        )
        assertNull(f.queue.state.value.currentEntryId)
        assertNull(f.queue.snapshot.value.current)
        assertEquals(listOf(tail.id), f.queue.state.value.traversal!!.upcoming)
    }

    @Test
    fun slowRestoreMetadataDoesNotBlockQueueCommands() = runTest {
        val f = PlaylistQueueFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val queue =
            QueueController(f.engine, f.coordinator) { id ->
                gate.await()
                f.tracks[id]
            }
        val restore = launch {
            queue.restore(QueueSnapshot(listOf(f.entry("a")), QueueEntryId("a")))
        }
        runCurrent()
        val add = launch { queue.dispatch(QueueCommand.Add(listOf(f.entry("b")))) }
        runCurrent()
        assertTrue(add.isCompleted)
        gate.complete(Unit)
        restore.join()
        add.join()
        assertEquals(listOf("a", "b"), queue.state.value.entries.map { it.id.value })
    }

    @Test
    fun slowStopCheckpointDoesNotBlockQueueCommandsOrStopANewerPlay() = runTest {
        val f = PlaylistQueueFixture(backgroundScope)
        f.start()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        val stopped = launch { f.queue.stop { gate.await() } }
        runCurrent()
        val adding = launch { f.queue.dispatch(QueueCommand.Add(listOf(f.entry("b")))) }
        runCurrent()
        assertTrue(adding.isCompleted)
        f.queue.play()
        runCurrent()
        gate.complete(Unit)
        stopped.join()
        assertTrue(f.queue.state.value.playWhenReady)
        assertEquals("a", f.engine.snapshot.value.current!!.id.value)
    }
}
