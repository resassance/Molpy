package app.musicplayer.ui.player

import app.musicplayer.model.*
import app.musicplayer.ui.sampleTrack
import kotlin.test.*

class QueuePresentationTest {
    @Test
    fun neighborsUseOccurrencesAndDoNotWrapAtEdges() {
        val a = sampleTrack("A")
        val b = sampleTrack("B")
        val entries =
            listOf(
                QueueEntry(QueueEntryId("a1"), a.id),
                QueueEntry(QueueEntryId("a2"), a.id),
                QueueEntry(QueueEntryId("b"), b.id),
            )
        val tracks = mapOf(a.id to a, b.id to b)
        val first = presentQueue(QueueSnapshot(entries, entries[0].id), tracks)
        assertNull(first.previous)
        assertEquals(entries[1].id, first.next?.entryId)
        val middle =
            presentQueue(
                QueueSnapshot(
                    entries,
                    entries[1].id,
                    traversal =
                        QueueTraversal(
                            history = listOf(entries[0].id),
                            upcoming = listOf(entries[2].id),
                        ),
                ),
                tracks,
            )
        assertEquals(entries[0].id, middle.previous?.entryId)
        assertEquals(entries[2].id, middle.next?.entryId)
        assertNull(presentQueue(QueueSnapshot(entries, entries[2].id), tracks).next)
    }
}
