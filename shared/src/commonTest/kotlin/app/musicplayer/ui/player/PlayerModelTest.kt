package app.musicplayer.ui.player

import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerModelTest {
    @Test
    fun restoredPauseIsDisplayedWithoutPlayAndModelCloseLeavesServiceAlone() = runTest {
        val port = EngineeringPort()
        val track = sampleTrack()
        val entry = QueueEntry(QueueEntryId("restored"), track.id)
        port.snapshot.value =
            PlaybackSnapshot(PlayerStatus.PAUSED, entry, track, 4321, 60000, false)
        val model =
            PlayerModel(
                port,
                EngineeringLibrary(),
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        assertEquals(track, model.state.value.snapshot.track)
        assertEquals(4321L, model.state.value.snapshot.positionMs)
        assertEquals(0, port.plays)
        model.close()
        assertEquals(track, port.snapshot.value.track)
        assertEquals(0, port.plays)
    }

    @Test
    fun queueLookupFailureDoesNotAlterAudioAndSettlesLoading() = runTest {
        val port = EngineeringPort()
        val track = sampleTrack()
        val entry = QueueEntry(QueueEntryId("playing"), track.id)
        port.snapshot.value =
            PlaybackSnapshot(PlayerStatus.PLAYING, entry, track, 1000, 60000, true)
        port.queue.value =
            QueueSnapshot(
                listOf(entry, QueueEntry(QueueEntryId("missing"), TrackId("missing"))),
                entry.id,
                1000,
                true,
            )
        val library =
            object : EngineeringLibrary() {
                override suspend fun getTrack(id: TrackId): Track? =
                    throw IllegalStateException("private failure")
            }
        val model =
            PlayerModel(
                port,
                library,
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        assertEquals(ErrorCode.DATABASE, model.state.value.repositoryError?.code)
        assertEquals(port.snapshot.value, model.state.value.snapshot)
        assertTrue(model.state.value.snapshot.playWhenReady)
        model.close()
    }

    @Test
    fun progressUpdatesDoNotReloadAllQueueMetadata() = runTest {
        val track = sampleTrack()
        val entry = QueueEntry(QueueEntryId("one"), track.id)
        val port = EngineeringPort().apply { queue.value = QueueSnapshot(listOf(entry), entry.id) }
        var reads = 0
        val library =
            object : EngineeringLibrary() {
                override suspend fun getTrack(id: TrackId): Track? {
                    reads++
                    return track
                }
            }
        val model =
            PlayerModel(
                port,
                library,
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        repeat(5) {
            port.queue.value = port.queue.value.copy(positionMs = (it + 1) * 250L)
            runCurrent()
        }
        assertEquals(1, reads)
        assertEquals(track, model.state.value.queueTracks[track.id])
        assertEquals(1250L, model.state.value.queue.positionMs)
        model.close()
    }

    @Test
    fun retryUsesRecoveredQueueMetadataWhenServiceLookupFailed() = runTest {
        val track = sampleTrack()
        val entry = QueueEntry(QueueEntryId("failed"), track.id)
        val port =
            EngineeringPort().apply {
                queue.value = QueueSnapshot(listOf(entry), entry.id, 1200, false)
                snapshot.value =
                    PlaybackSnapshot(
                        PlayerStatus.FAILED,
                        entry,
                        positionMs = 1200,
                        playWhenReady = false,
                        error = AppError(ErrorCode.DATABASE, "queue.lookup", true),
                    )
            }
        val records = EngineeringLibrary().apply { tracks[track.id] = track }
        val model =
            PlayerModel(
                port,
                records,
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0L },
                backgroundScope,
            )
        runCurrent()
        assertEquals(track, model.state.value.currentTrack)
        assertNull(model.state.value.snapshot.track)
        model.retry()
        runCurrent()
        assertEquals(listOf(entry), port.selections)
        assertEquals(1200L, port.snapshot.value.positionMs)
        assertTrue(port.snapshot.value.playWhenReady)
        model.close()
    }
}
