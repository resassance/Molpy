package app.musicplayer.queue

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePlaylistRecoveryTest {
    @Test
    fun selectingKnownRestoredEntryWithPlayIntentStartsSourceRecovery() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val saved = restored(h)
        h.catalog.head = { _, _ -> head(h, listOf("a", "b", "c")) }
        assertIs<AppResult.Success<Unit>>(h.playback.queue.select(saved.entries[1], 0, true))
        runCurrent()
        assertEquals(1, h.catalog.headCalls)
        assertEquals(listOf("c"), h.future())
        assertEquals("b", h.playback.engine.snapshot.value.track!!.id.value)
    }

    private suspend fun restored(
        h: QueuePlaylistTestFixture,
        ids: List<String> = listOf("a", "b"),
    ): QueueSnapshot {
        ids.forEach { h.playback.tracks[TrackId(it)] = h.track(it) }
        val entries = ids.mapIndexed { i, id -> QueueEntry(QueueEntryId("saved-$i"), TrackId(id)) }
        val snapshot =
            QueueSnapshot(
                entries,
                entries.first().id,
                source =
                    QueueSourceState(
                        "restored",
                        h.ref,
                        0,
                        h.page(0, ids).items.map { it.identity },
                    ),
            )
        h.playback.queue.restore(snapshot)
        return h.playback.queue.state.value
    }

    private fun head(h: QueuePlaylistTestFixture, ids: List<String>, next: String? = null) =
        AppResult.Success(
            PublicPlaylistCatalog(PublicPlaylistCard(h.ref, null, null, null), h.page(0, ids, next))
        )

    @Test
    fun restoreStaysPausedUntilPlayAndMatchingPrefixDoesNotDuplicate() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val original = restored(h)
        h.catalog.head = { _, refresh ->
            assertTrue(refresh)
            head(h, listOf("a", "b", "c"))
        }
        runCurrent()
        assertEquals(0, h.catalog.headCalls)
        assertEquals(0, h.playback.resolves)
        assertFalse(h.playback.queue.state.value.playWhenReady)
        h.loader.resumeIfNeeded()
        h.playback.queue.play()
        runCurrent()
        assertEquals(
            listOf("a", "b", "c"),
            h.playback.queue.state.value.entries.map { it.trackId.value },
        )
        assertEquals(
            original.entries.map { it.id },
            h.playback.queue.state.value.entries.take(2).map { it.id },
        )
        assertEquals(1, h.playback.resolves)
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun changedOrderStopsExtensionButKeepsKnownQueue() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val original = restored(h)
        h.catalog.head = { _, _ -> head(h, listOf("b", "a", "c")) }
        assertIs<AppResult.Failure>(h.loader.retry())
        assertEquals(original.entries, h.playback.queue.state.value.entries)
        assertEquals(QueueSourcePhase.CHANGED, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun removedEntryDoesNotReturnOnReplayAcrossPages() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val original = restored(h)
        h.playback.queue.dispatch(QueueCommand.Remove(original.entries[1].id))
        h.catalog.head = { _, _ -> head(h, listOf("a"), "fresh") }
        h.catalog.read = { AppResult.Success(h.page(1, listOf("b", "c"))) }
        assertIs<AppResult.Success<Unit>>(h.loader.retry())
        assertEquals(
            listOf("a", "c"),
            h.playback.queue.state.value.entries.map { it.trackId.value },
        )
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun oldNetworkResponseCannotCommitAfterFreshRecovery() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.catalog.read = {
            withContext(NonCancellable) { gate.await() }
            AppResult.Success(h.page(1, listOf("old")))
        }
        h.loader.play(h.request())
        runCurrent()
        h.loader.networkInvalidated()
        h.catalog.head = { _, _ -> head(h, listOf("a", "new")) }
        h.loader.networkActivated()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), h.future())
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun invalidatedCursorIsRecoveredByFreshPrefix() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        h.catalog.read = {
            AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.cursor", false))
        }
        h.catalog.head = { _, _ -> head(h, listOf("a", "c")) }
        h.loader.play(h.request())
        runCurrent()
        assertEquals(listOf("c"), h.future())
        assertEquals(1, h.catalog.headCalls)
    }

    @Test
    fun sourceDeletedPreservesKnownQueueAndDoesNotComplete() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val original = restored(h)
        h.catalog.head = { _, _ ->
            AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.playlist", false))
        }
        assertIs<AppResult.Failure>(h.loader.retry())
        assertEquals(original.entries, h.playback.queue.state.value.entries)
        assertEquals(QueueSourcePhase.FAILED, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun networkChangeDuringPendingAcknowledgementKeepsTheFreshCheckpoint() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.beforeCommit = { if (it.source?.acceptedRows?.size == 2) gate.await() }
        h.catalog.read = { AppResult.Success(h.page(1, listOf("b"), "last")) }
        h.loader.play(h.request())
        runCurrent()
        assertEquals(listOf("b"), h.future())
        h.loader.networkInvalidated()
        h.catalog.head = { _, _ -> head(h, listOf("a", "b", "c")) }
        h.loader.networkActivated()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b", "c"), h.future())
        assertEquals(3, h.durable.source!!.acceptedRows.size)
        assertEquals(QueueSourcePhase.COMPLETE, h.durable.source!!.phase)
    }

    @Test
    fun stoppedSourceDoesNotResumeWhenNetworkReturns() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        restored(h)
        h.loader.stop()
        h.loader.networkInvalidated()
        h.loader.networkActivated()
        runCurrent()
        assertEquals(0, h.catalog.headCalls)
        assertEquals(0, h.playback.resolves)
    }
}
