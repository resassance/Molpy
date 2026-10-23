package app.musicplayer.ui.player

import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingModelTest {
    @Test
    fun lyricsFollowActualCandidateAndOldResponseCannotReplaceNewSelection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = sampleTrack("A")
        val current = sampleTrack("B")
        val port = EngineeringPort()
        val loader =
            LyricsLoader(
                listOf(
                    object : LyricsPort {
                        override suspend fun fetch(
                            track: Track,
                            candidate: PlaybackCandidate,
                        ): AppResult<LyricsCandidate?> {
                            if (track.id == old.id) withContext(NonCancellable) { gate.await() }
                            return AppResult.Success(
                                LyricsCandidate(
                                    NormalizedLyrics(
                                        track.title,
                                        emptyList(),
                                        LyricsKind.PLAIN,
                                        SourceRef("lyrics", "entry", track.title),
                                    ),
                                    true,
                                    false,
                                )
                            )
                        }
                    }
                ),
                LyricsSelector(),
                SessionLyricsCache(),
            ) {
                0
            }
        port.snapshot.value = snapshot(old)
        val model = PlayerModel(port, EngineeringLibrary(), loader, backgroundScope)
        runCurrent()
        port.snapshot.value = snapshot(current)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("B", (model.state.value.lyrics as LyricsLookup.Available).lyrics.plainText)
        assertFalse(model.state.value.loadingLyrics)
        assertEquals(port.snapshot.value, model.state.value.snapshot)
        model.close()
    }

    @Test
    fun cacheFailureSettlesLyricsWithoutTouchingActualAudio() = runTest {
        val track = sampleTrack("A")
        val port = EngineeringPort().apply { snapshot.value = snapshot(track) }
        val cache =
            object : LyricsCache {
                override suspend fun getLyrics(
                    id: TrackId,
                    candidateId: CandidateId,
                    nowMs: Long,
                ): LyricsLookup? = throw IllegalStateException("private")

                override suspend fun saveLyrics(
                    id: TrackId,
                    candidateId: CandidateId,
                    lookup: LyricsLookup,
                    atMs: Long,
                ) {}
            }
        val model =
            PlayerModel(
                port,
                EngineeringLibrary(),
                LyricsLoader(emptyList(), LyricsSelector(), cache) { 0 },
                backgroundScope,
            )
        runCurrent()
        assertEquals(ErrorCode.DATABASE, model.state.value.lyricsError?.code)
        assertFalse(model.state.value.loadingLyrics)
        assertEquals(PlayerStatus.PLAYING, model.state.value.snapshot.status)
        assertTrue(model.state.value.snapshot.playWhenReady)
        model.close()
    }

    @Test
    fun queueRowStartsSelectedOccurrenceFromPausedStateAndMoveUsesEntryId() = runTest {
        val track = sampleTrack("A")
        val first = QueueEntry(QueueEntryId("a1"), track.id)
        val second = QueueEntry(QueueEntryId("a2"), track.id)
        val library = EngineeringLibrary().apply { tracks[track.id] = track }
        val port =
            EngineeringPort().apply {
                queue.value = QueueSnapshot(listOf(first, second), first.id, 0, false)
            }
        val model =
            PlayerModel(
                port,
                library,
                LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0 },
                backgroundScope,
            )
        runCurrent()
        model.selectEntry(second.id)
        runCurrent()
        assertEquals(second, port.snapshot.value.current)
        assertTrue(port.snapshot.value.playWhenReady)
        model.moveEntry(second.id, 0)
        runCurrent()
        assertEquals(
            second.id,
            app.musicplayer.queue.QueueOrder.upcoming(port.queue.value).first().id,
        )
        model.removeEntry(first.id)
        runCurrent()
        assertEquals(listOf(second), port.queue.value.entries)
        model.close()
    }

    private fun snapshot(track: Track) =
        PlaybackSnapshot(
            PlayerStatus.PLAYING,
            QueueEntry(QueueEntryId(track.id.value), track.id),
            track,
            0,
            track.durationMs,
            true,
            candidateId = track.candidates.single().id,
        )
}
