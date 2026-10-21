package app.musicplayer.ui.player

import app.musicplayer.model.*
import app.musicplayer.ui.sampleTrack
import kotlin.test.*

class PlayerDurationTest {
    private val track =
        sampleTrack().let {
            it.copy(
                durationMs = 190_000,
                candidates = listOf(it.candidates.first().copy(durationMs = 189_000)),
            )
        }

    @Test
    fun durationUsesMeasuredThenSelectedCandidate() {
        val state =
            PlayerState(
                snapshot =
                    PlaybackSnapshot(track = track, candidateId = track.candidates.first().id)
            )
        assertEquals(189_000L, state.effectiveDurationMs)
        assertEquals(
            188_000L,
            state.copy(snapshot = state.snapshot.copy(durationMs = 188_000)).effectiveDurationMs,
        )
        assertEquals(
            189_000L,
            state.copy(snapshot = state.snapshot.copy(durationMs = 0)).effectiveDurationMs,
        )
    }

    @Test
    fun unknownAndZeroDurationsStayUnknown() {
        val unknown =
            track.copy(
                durationMs = 0,
                candidates = track.candidates.map { it.copy(durationMs = null) },
            )
        assertNull(
            PlayerState(snapshot = PlaybackSnapshot(track = unknown, durationMs = 0))
                .effectiveDurationMs
        )
    }

    @Test
    fun anotherCandidateCannotSupplySelectedVersionDuration() {
        val other = track.candidates.first().copy(id = CandidateId("other"), durationMs = null)
        val selected = track.copy(durationMs = null, candidates = track.candidates + other)
        assertNull(
            PlayerState(snapshot = PlaybackSnapshot(track = selected, candidateId = other.id))
                .effectiveDurationMs
        )
    }
}
