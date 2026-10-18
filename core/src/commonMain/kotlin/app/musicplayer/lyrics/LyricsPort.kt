package app.musicplayer.lyrics

import app.musicplayer.model.*

data class LyricsCandidate(val lyrics: NormalizedLyrics, val versionMatched: Boolean, val timingValid: Boolean)

interface LyricsPort {
    /** A successful null means known absence; failures and cancellation remain distinct. */
    suspend fun fetch(track: Track, candidate: PlaybackCandidate): AppResult<LyricsCandidate?>
}
