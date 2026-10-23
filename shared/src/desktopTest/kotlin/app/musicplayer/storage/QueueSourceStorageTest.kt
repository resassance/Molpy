package app.musicplayer.storage

import androidx.room.useWriterConnection
import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.queue.QueueOrder
import kotlin.test.*
import kotlinx.coroutines.*

class QueueSourceStorageTest {
    private fun source(rows: Int) =
        QueueSourceState(
            "session",
            PublicPlaylistRef("Mix", SourceRef("youtube", "playlist", "PLtest")),
            0,
            (0 until rows).map {
                PlaylistRowIdentity(it, "row-$it", SourceRef("youtube", "video", "song-$it"))
            },
            shuffleRequested = true,
        )

    private fun queue(rows: Int) =
        QueueOrder.normalized(
            QueueSnapshot(
                (0 until rows).map { QueueEntry(QueueEntryId("entry-$it"), TrackId("track-$it")) },
                QueueEntryId("entry-0"),
                source = source(rows),
                revision = rows.toLong(),
            )
        )

    @Test
    fun sourcePageAndMetadataCommitTogether() = runBlocking {
        val l = TestLibrary()
        try {
            l.open()
            l.repository.saveQueue(queue(1), listOf(fixture("track-0")))
            l.repository.saveQueue(queue(2), listOf(fixture("track-1")))
            l.reopen()
            assertEquals(queue(2), l.repository.restoreQueue())
            assertEquals("Same title", l.repository.getTrack(TrackId("track-1"))!!.title)
            assertEquals(2L, l.repository.restoreQueue().revision)
        } finally {
            l.close()
        }
    }

    @Test
    fun failedPageRollsBackMetadataAndCheckpoint() = runBlocking {
        val l = TestLibrary()
        try {
            l.open()
            l.repository.saveQueue(queue(1), listOf(fixture("track-0")))
            l.db.useWriterConnection { c ->
                c.usePrepared(
                    "CREATE TRIGGER reject_source BEFORE INSERT ON queue_snapshot BEGIN SELECT RAISE(ABORT, 'fixture'); END"
                ) {
                    it.step()
                }
            }
            assertFails { l.repository.saveQueue(queue(2), listOf(fixture("track-1"))) }
            assertNull(l.repository.getTrack(TrackId("track-1")))
            assertEquals(queue(1), l.repository.restoreQueue())
        } finally {
            l.close()
        }
    }

    @Test
    fun cancelledCallerRollsBackSourceCommit() = runBlocking {
        val l = TestLibrary()
        try {
            l.open()
            l.repository.saveQueue(queue(1), listOf(fixture("track-0")))
            lateinit var command: Deferred<Unit>
            val cancelling =
                RoomLibraryRepository(l.db) {
                    command.cancel()
                    0
                }
            command =
                async(start = CoroutineStart.LAZY) {
                    cancelling.saveQueue(queue(2), listOf(fixture("track-1")))
                }
            assertFailsWith<CancellationException> { command.await() }
            assertNull(l.repository.getTrack(TrackId("track-1")))
            assertEquals(queue(1), l.repository.restoreQueue())
        } finally {
            l.close()
        }
    }

    @Test
    fun olderRevisionCannotOverwriteAcceptedSourceAndEqualRevisionCannotChangeIt() = runBlocking {
        val l = TestLibrary()
        try {
            l.open()
            l.repository.saveQueue(queue(2), listOf(fixture("track-0"), fixture("track-1")))
            l.repository.saveQueue(queue(1))
            assertEquals(queue(2), l.repository.restoreQueue())
            assertFailsWith<IllegalArgumentException> {
                l.repository.saveQueue(queue(2).copy(source = source(1)))
            }
            assertEquals(queue(2), l.repository.restoreQueue())
        } finally {
            l.close()
        }
    }

    @Test
    fun corruptSourceKeepsKnownQueueWithoutInventingPlaylistIdentity() = runBlocking {
        val l = TestLibrary()
        try {
            l.open()
            l.repository.saveQueue(queue(2), listOf(fixture("track-0"), fixture("track-1")))
            val saved = l.db.dao().queueSnapshot()!!
            l.db.dao().putQueueSnapshot(saved.copy(sourceJson = "{broken"))
            val restored = l.repository.restoreQueue()
            assertEquals(queue(2).entries, restored.entries)
            assertNull(restored.source)
            assertEquals(ErrorCode.PROVIDER_CHANGED, restored.sourceRecoveryError!!.code)
            assertFalse(restored.playWhenReady)
        } finally {
            l.close()
        }
    }

    @Test
    fun missingCurrentKeepsUnknownBoundaryBeforeManualTail() = runBlocking {
        val l = TestLibrary()
        try {
            l.open()
            val tail = QueueEntry(QueueEntryId("tail"), TrackId("tail"))
            val q =
                queue(1)
                    .copy(
                        entries = queue(1).entries + tail,
                        traversal = QueueTraversal(upcoming = listOf(tail.id)),
                        source = source(1).copy(deferredEndIds = listOf(tail.id)),
                    )
            l.repository.saveQueue(q, listOf(fixture("track-0"), fixture("tail")))
            l.db.dao().deleteTrack("track-0")
            val restored = l.repository.restoreQueue()
            assertNull(restored.currentEntryId)
            assertTrue(restored.source!!.waiting)
            assertEquals(listOf(tail), restored.entries)
            assertEquals(listOf(tail.id), restored.traversal!!.upcoming)
            assertEquals(listOf(tail.id), restored.source!!.deferredEndIds)
        } finally {
            l.close()
        }
    }
}
