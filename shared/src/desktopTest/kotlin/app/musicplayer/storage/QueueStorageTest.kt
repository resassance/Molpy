package app.musicplayer.storage

import androidx.room.useWriterConnection
import app.musicplayer.model.*
import app.musicplayer.queue.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class QueueStorageTest {
    @Test
    fun queueStateSurvivesReopenWithPriorityHistoryAndMode() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val tracks = listOf("a", "b", "c", "x", "y").map { fixture(it) }
            tracks.forEach { library.repository.upsert(it) }
            val entries = tracks.map { QueueEntry(QueueEntryId(it.id.value), it.id) }
            var q =
                QueueReducer.reduce(
                        QueueSnapshot(),
                        QueueCommand.Replace(entries.take(3), entries[0].id),
                    )
                    .snapshot
            q = QueueReducer.reduce(q, QueueCommand.Next).snapshot
            q =
                QueueReducer.reduce(q, QueueCommand.Add(listOf(entries[3]), QueueInsertion.NEXT))
                    .snapshot
            q =
                QueueReducer.reduce(q, QueueCommand.Add(listOf(entries[4]), QueueInsertion.NEXT))
                    .snapshot
            q =
                QueueReducer.reduce(q, QueueCommand.SetShuffle(true), Random(2))
                    .snapshot
                    .copy(positionMs = 2345)
            library.repository.saveQueue(q)
            library.reopen()
            val restored = library.repository.restoreQueue()
            assertEquals(q.copy(playWhenReady = false), restored)
            assertEquals(listOf(entries[4].id, entries[3].id), restored.traversal!!.priority)
        } finally {
            library.close()
        }
    }

    @Test
    fun emptyQueueKeepsShufflePreference() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val q = QueueSnapshot(shuffleEnabled = true, traversal = QueueTraversal())
            library.repository.saveQueue(q)
            library.reopen()
            assertEquals(q, library.repository.restoreQueue())
        } finally {
            library.close()
        }
    }

    @Test
    fun corruptPayloadResetsWholeTraversalAndKeepsCurrentPosition() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val tracks = listOf("a", "b", "c").map { fixture(it) }
            tracks.forEach { library.repository.upsert(it) }
            val entries = tracks.map { QueueEntry(QueueEntryId(it.id.value), it.id) }
            val original = QueueSnapshot(entries, entries[1].id, 3456, false)
            library.repository.saveQueue(original)
            val saved = library.db.dao().queueSnapshot()!!
            val payloads =
                listOf(
                    "broken",
                    """{"version":99,"history":[],"upcoming":["c"],"priority":[]}""",
                    """{"version":1,"history":[],"upcoming":["c"],"priority":["a"]}""",
                    """{"version":1,"history":[],"upcoming":["c","c"],"priority":[]}""",
                )
            payloads.forEach { raw ->
                library.db
                    .dao()
                    .putQueueSnapshot(saved.copy(shuffleEnabled = true, traversalJson = raw))
                val restored = library.repository.restoreQueue()
                assertFalse(restored.shuffleEnabled)
                assertEquals(3456L, restored.positionMs)
                assertEquals(listOf(entries[2].id), restored.traversal!!.upcoming)
                assertTrue(restored.traversal!!.history.isEmpty())
            }
        } finally {
            library.close()
        }
    }

    @Test
    fun missingCurrentAndPriorityReferencesArePrunedTogether() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val tracks = listOf("a", "b", "c").map { fixture(it) }
            tracks.forEach { library.repository.upsert(it) }
            val entries = tracks.map { QueueEntry(QueueEntryId(it.id.value), it.id) }
            val q =
                QueueSnapshot(
                    entries,
                    entries[0].id,
                    3456,
                    true,
                    true,
                    QueueTraversal(
                        upcoming = listOf(entries[2].id, entries[1].id),
                        priority = listOf(entries[2].id),
                    ),
                )
            library.repository.saveQueue(q)
            library.db.dao().deleteTrack(tracks[0].id.value)
            val restored = library.repository.restoreQueue()
            assertEquals(entries[2].id, restored.currentEntryId)
            assertEquals(0L, restored.positionMs)
            assertEquals(listOf(entries[1].id), restored.traversal!!.upcoming)
            assertTrue(restored.traversal!!.priority.isEmpty())
            assertTrue(restored.shuffleEnabled)
            assertFalse(restored.playWhenReady)
        } finally {
            library.close()
        }
    }

    @Test
    fun failingSnapshotWriteRollsBackEntryReplacement() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val a = fixture("a")
            val b = fixture("b")
            library.repository.upsert(a)
            library.repository.upsert(b)
            val old =
                QueueOrder.normalized(
                    QueueSnapshot(listOf(QueueEntry(QueueEntryId("a"), a.id)), QueueEntryId("a"))
                )
            library.repository.saveQueue(old)
            library.db.useWriterConnection { c ->
                c.usePrepared(
                    "CREATE TRIGGER reject_queue BEFORE INSERT ON queue_snapshot BEGIN SELECT RAISE(ABORT, 'fixture'); END"
                ) {
                    it.step()
                }
            }
            assertFails {
                library.repository.saveQueue(
                    QueueSnapshot(listOf(QueueEntry(QueueEntryId("b"), b.id)), QueueEntryId("b"))
                )
            }
            library.db.useWriterConnection { c ->
                c.usePrepared("DROP TRIGGER reject_queue") { it.step() }
            }
            assertEquals(old, library.repository.restoreQueue())
        } finally {
            library.close()
        }
    }
}
