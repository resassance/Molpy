package app.musicplayer.lyrics

import app.musicplayer.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session cache. Only confirmed absence expires after 24 hours; transient failures are never stored. */
class SessionLyricsCache : LyricsCache {
    private data class Entry(val lookup: LyricsLookup, val atMs: Long)
    private val mutex = Mutex()
    private val entries = mutableMapOf<Pair<TrackId, CandidateId>, Entry>()

    override suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup? = mutex.withLock {
        val key = id to candidateId
        val entry = entries[key] ?: return@withLock null
        if (entry.lookup == LyricsLookup.Unavailable && (nowMs < entry.atMs || nowMs - entry.atMs >= ABSENCE_TTL_MS)) {
            entries.remove(key)
            null
        } else entry.lookup
    }

    override suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long) = mutex.withLock {
        if (lookup is LyricsLookup.Failed) return@withLock
        val key = id to candidateId
        val old = entries[key]
        if (old == null || quality(lookup) >= quality(old.lookup)) entries[key] = Entry(lookup, atMs)
    }

    private fun quality(lookup: LyricsLookup) = when (lookup) {
        is LyricsLookup.Available -> if (lookup.lyrics.kind == LyricsKind.LINE_SYNCED) 3 else 2
        is LyricsLookup.Instrumental -> 1
        LyricsLookup.Unavailable, is LyricsLookup.Failed -> 0
    }

    private companion object { const val ABSENCE_TTL_MS = 24L * 60 * 60 * 1000 }
}
