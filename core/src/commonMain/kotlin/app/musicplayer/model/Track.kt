package app.musicplayer.model

@JvmInline value class TrackId(val value: String) {
    init { require(value.isNotBlank()) { "Track ID must not be blank" } }
}
@JvmInline value class CandidateId(val value: String) {
    init { require(value.isNotBlank()) { "Candidate ID must not be blank" } }
}
@JvmInline value class QueueEntryId(val value: String) {
    init { require(value.isNotBlank()) { "Queue entry ID must not be blank" } }
}
@JvmInline value class AssetId(val value: String) {
    init { require(value.isNotBlank()) { "Asset ID must not be blank" } }
}
@JvmInline value class SecretId(val value: String) {
    init { require(value.isNotBlank()) { "Secret ID must not be blank" } }
}

data class Track(
    val id: TrackId,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val artwork: Artwork,
    val durationMs: Long?,
    val externalIds: Set<SourceRef>,
    val provenance: MetadataProvenance,
    val candidates: List<PlaybackCandidate>,
)

data class MetadataProvenance(val source: SourceRef, val fetchedAtMs: Long, val tentative: Boolean)
enum class ArtworkKind { RELEASE, VIDEO, PLACEHOLDER }
data class Artwork(val kind: ArtworkKind, val remoteUrl: String?, val localKey: String?)
enum class MatchConfidence { HIGH, TENTATIVE }
data class PlaybackCandidate(
    val id: CandidateId,
    val source: SourceRef,
    val version: String,
    val durationMs: Long?,
    val confidence: MatchConfidence,
)
