package app.musicplayer.lyrics

import app.musicplayer.model.*

interface LyricsCache {
    suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup?
    suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long)
}
