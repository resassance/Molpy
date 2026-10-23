package app.musicplayer.model

data class QueueEntry(val id: QueueEntryId, val trackId: TrackId)

data class QueueTraversal(
    val history: List<QueueEntryId> = emptyList(),
    val upcoming: List<QueueEntryId> = emptyList(),
    val priority: List<QueueEntryId> = emptyList(),
)

/** Durable identities and playback intent only; no resolved media URLs. */
data class QueueSnapshot(
    val entries: List<QueueEntry> = emptyList(),
    val currentEntryId: QueueEntryId? = null,
    val positionMs: Long = 0,
    val playWhenReady: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val traversal: QueueTraversal? = null,
)
