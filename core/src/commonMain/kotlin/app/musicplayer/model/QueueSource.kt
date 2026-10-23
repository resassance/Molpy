package app.musicplayer.model

import app.musicplayer.catalog.PlaylistRowIdentity
import app.musicplayer.catalog.PublicPlaylistRef

enum class QueueSourcePhase {
    LOADING,
    FAILED,
    CHANGED,
    COMPLETE,
    DETACHED,
}

data class QueueSourceState(
    val sessionId: String,
    val ref: PublicPlaylistRef,
    val selectedOrdinal: Int,
    val acceptedRows: List<PlaylistRowIdentity>,
    val deferredEndIds: List<QueueEntryId> = emptyList(),
    val phase: QueueSourcePhase = QueueSourcePhase.LOADING,
    val waiting: Boolean = false,
    val shuffleRequested: Boolean = false,
    val error: AppError? = null,
) {
    val hasMore: Boolean
        get() = phase != QueueSourcePhase.COMPLETE && phase != QueueSourcePhase.DETACHED
}
