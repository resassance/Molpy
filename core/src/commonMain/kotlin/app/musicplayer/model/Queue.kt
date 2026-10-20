package app.musicplayer.model

data class QueueEntry(val id: QueueEntryId, val trackId: TrackId)

/** Durable identities and playback intent only; no resolved media URLs. */
data class QueueSnapshot(
    val entries: List<QueueEntry> = emptyList(),
    val currentEntryId: QueueEntryId? = null,
    val positionMs: Long = 0,
    val playWhenReady: Boolean = false,
)
