package app.musicplayer.model

enum class TransportKind { PROGRESSIVE, HLS, BOUNDED_RANGE }

/** Stable format descriptor; it deliberately contains no request URL or headers. */
data class Representation(
    val id: String,
    val mimeType: String,
    val codec: String,
    val expectedBytes: Long?,
    val expectedDurationMs: Long?,
    val validator: String?,
)

/** Runtime-only request. Never persist this object, or log its URI or header values. */
class MediaRequest(val uri: String, headers: Map<String, String>) {
    val headers: Map<String, String> = headers.toMap()
    override fun toString(): String = "MediaRequest(uri=redacted, headers=redacted)"
}

/** A session lease, not durable track metadata. Expiry may be unknown for immediate playback. */
data class ResolvedPlayback(
    val candidateId: CandidateId,
    val representation: Representation,
    val transport: TransportKind,
    val request: MediaRequest,
    val expiresAtMs: Long?,
    val networkGeneration: Long,
)

data class PlaybackCapabilities(val transports: Set<TransportKind>)

/** Local is a future offline contract; the current Android engine rejects it explicitly. */
sealed interface PlayableSource {
    val track: Track
    data class Remote(val lease: ResolvedPlayback, override val track: Track) : PlayableSource
    data class Local(val assetId: AssetId, val stableUri: String, val mimeType: String,
                     val cacheKey: String, override val track: Track) : PlayableSource
}

enum class PlayerStatus { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ENDED, FAILED }

data class PlaybackSnapshot(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val current: QueueEntry? = null,
    val track: Track? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val playWhenReady: Boolean = false,
    val error: AppError? = null,
)
