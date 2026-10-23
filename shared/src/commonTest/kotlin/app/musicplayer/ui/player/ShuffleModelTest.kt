package app.musicplayer.ui.player

import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.queue.*
import app.musicplayer.ui.*
import app.musicplayer.ui.components.TrackActions
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShuffleModelTest {
    @Test
    fun queuePresentationUsesHistoryAndFutureInsteadOfCanonicalIndices() {
        val tracks = listOf("a", "b", "c", "d").map { sampleTrack(it) }
        val entries = tracks.map { QueueEntry(QueueEntryId(it.id.value), it.id) }
        val q =
            QueueSnapshot(
                entries,
                entries[3].id,
                traversal =
                    QueueTraversal(listOf(entries[0].id), listOf(entries[1].id, entries[2].id)),
            )
        val shown = presentQueue(q, tracks.associateBy { it.id })
        assertEquals(entries[0].id, shown.previous?.entryId)
        assertEquals(entries[1].id, shown.next?.entryId)
        assertEquals(listOf(entries[1].id, entries[2].id), shown.upcoming.map { it.entryId })
    }

    @Test
    fun twoToggleClicksSendTwoEventsWithoutChangingCurrentTrack() = runTest {
        val port = EngineeringPort()
        val track = sampleTrack()
        val entry = QueueEntry(QueueEntryId("a"), track.id)
        port.queue.value = QueueSnapshot(listOf(entry), entry.id, traversal = QueueTraversal())
        port.snapshot.value =
            PlaybackSnapshot(PlayerStatus.PLAYING, entry, track, positionMs = 4321)
        val model =
            PlayerModel(
                port,
                EngineeringLibrary(),
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        model.toggleShuffle()
        model.toggleShuffle()
        runCurrent()
        assertEquals(
            listOf<QueueCommand>(QueueCommand.ToggleShuffle, QueueCommand.ToggleShuffle),
            port.commands,
        )
        assertFalse(model.state.value.queue.shuffleEnabled)
        assertEquals(4321L, model.state.value.snapshot.positionMs)
        model.close()
    }

    @Test
    fun trackActionsPreserveRawTrackAndInsertionPlacement() = runTest {
        var saved: Track? = null
        var placement: QueueInsertion? = null
        val port =
            object : EngineeringPort() {
                override suspend fun enqueue(
                    track: Track,
                    position: QueueInsertion,
                ): AppResult<Unit> {
                    saved = track
                    placement = position
                    return AppResult.Success(Unit)
                }
            }
        val track = sampleTrack()
        val actions =
            TrackActions(EngineeringLibrary(), MutableCollections(), port, PresentationDownloads())
        actions.enqueue(track, QueueInsertion.NEXT)
        assertSame(track, saved)
        assertEquals(QueueInsertion.NEXT, placement)
        actions.enqueue(track, QueueInsertion.END)
        assertSame(track, saved)
        assertEquals(QueueInsertion.END, placement)
    }
}
