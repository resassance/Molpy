package app.musicplayer.downloads

import app.musicplayer.model.*

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, AVAILABLE, FAILED }

/** Durable identity and a projection of platform-owned transfer state, never a network request. */
data class OfflineAsset(
    val id: AssetId,
    val trackId: TrackId,
    val trackSnapshot: Track,
    val candidateId: CandidateId,
    val source: SourceRef,
    val representation: Representation,
    val storageKey: String,
    val bytes: Long,
    val state: DownloadState,
) {
    init {
        require(trackSnapshot.id == trackId) { "Offline snapshot must match track identity" }
        require(trackSnapshot.candidates.singleOrNull { it.id == candidateId }?.source == source) {
            "Offline candidate and source must match the downloaded snapshot"
        }
        require(bytes >= 0) { "Stored bytes cannot be negative" }
        require(storageKey.isNotBlank() && !storageKey.contains("://") && !storageKey.startsWith("//")) {
            "Storage key must be an app-owned local identity"
        }
        require(representation.id.isNotBlank() && !representation.id.contains("://")) {
            "Representation ID must be a stable format identity"
        }
    }
}
