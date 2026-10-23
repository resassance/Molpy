package app.musicplayer.listening

import app.musicplayer.model.*

data class ListeningCheckpoint(
    val sessionId: String,
    val revision: Long,
    val entryId: QueueEntryId,
    val track: Track,
    val startedAtMs: Long,
    val lastPlayedAtMs: Long,
    val playedMs: Long,
)

data class TrackListening(val track: Track, val playedMs: Long, val lastPlayedAtMs: Long)

data class ArtistListening(
    val key: String,
    val artist: ArtistRef,
    val playedMs: Long,
    val lastPlayedAtMs: Long,
)

data class ListeningProfile(
    val totalMs: Long = 0,
    val tracks: List<TrackListening> = emptyList(),
    val artists: List<ArtistListening> = emptyList(),
    val artistRevision: Long = 0,
)

data class HistoryItem(val id: String, val track: Track, val startedAtMs: Long, val playedMs: Long?)

data class HistoryCursor(val startedAtMs: Long, val id: String)

data class HistoryPage(val items: List<HistoryItem>, val next: HistoryCursor?)
