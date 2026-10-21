package app.musicplayer.ui.player

import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.queue.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerMotionTest {
    @Test
    fun transportMovesDirectionallyWhileSelectingAdjacentEntryFades() = runTest {
        val tracks = listOf(sampleTrack("A"), sampleTrack("B"), sampleTrack("C"))
        val entries = tracks.mapIndexed { index, track ->
            QueueEntry(QueueEntryId("entry-$index"), track.id)
        }
        val port =
            object : EngineeringPort() {
                override suspend fun dispatch(command: QueueCommand) {
                    super.dispatch(command)
                    val entry = queue.value.entries.first { it.id == queue.value.currentEntryId }
                    snapshot.value =
                        PlaybackSnapshot(
                            PlayerStatus.RESOLVING,
                            entry,
                            tracks.first { it.id == entry.trackId },
                        )
                }
            }
        port.queue.value = QueueSnapshot(entries, entries[0].id)
        port.snapshot.value = PlaybackSnapshot(PlayerStatus.PAUSED, entries[0], tracks[0])
        val library = EngineeringLibrary().apply { tracks.forEach { this.tracks[it.id] = it } }
        val model =
            PlayerModel(
                port,
                library,
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        model.next()
        runCurrent()
        assertEquals(entries[1], model.state.value.snapshot.current)
        assertEquals(TrackMotion.NEXT, model.state.value.trackMotion)
        port.snapshot.value =
            port.snapshot.value.copy(positionMs = 250, status = PlayerStatus.PLAYING)
        runCurrent()
        assertEquals(TrackMotion.NEXT, model.state.value.trackMotion)
        model.previous()
        runCurrent()
        assertEquals(entries[0], model.state.value.snapshot.current)
        assertEquals(TrackMotion.PREVIOUS, model.state.value.trackMotion)
        model.selectEntry(entries[1].id)
        runCurrent()
        assertEquals(entries[1], model.state.value.snapshot.current)
        assertEquals(TrackMotion.REPLACE, model.state.value.trackMotion)
        model.close()
    }

    @Test
    fun systemNextAndRepeatedTrackOccurrencesUseQueueEntryIdentity() = runTest {
        val track = sampleTrack()
        val first = QueueEntry(QueueEntryId("first"), track.id)
        val second = QueueEntry(QueueEntryId("second"), track.id)
        val port =
            EngineeringPort().apply {
                queue.value = QueueSnapshot(listOf(first, second), first.id)
                snapshot.value = PlaybackSnapshot(PlayerStatus.PLAYING, first, track)
            }
        val model =
            PlayerModel(
                port,
                EngineeringLibrary(),
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        port.queue.value = port.queue.value.copy(currentEntryId = second.id)
        port.snapshot.value = port.snapshot.value.copy(current = second, positionMs = 0)
        runCurrent()
        assertEquals(TrackMotion.NEXT, model.state.value.trackMotion)
        port.snapshot.value = port.snapshot.value.copy(positionMs = 250)
        runCurrent()
        assertEquals(TrackMotion.NEXT, model.state.value.trackMotion)
        model.close()
    }
}
