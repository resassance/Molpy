package app.musicplayer.ui

import app.musicplayer.model.*
import app.musicplayer.downloads.*

data class TechnicalState(
    val queue: QueueSnapshot = QueueSnapshot(),
    val queueTracks: Map<TrackId, Track> = emptyMap(),
    val favorites: List<Track> = emptyList(),
    val recent: List<Track> = emptyList(),
    val networkDraft: NetworkDraft = NetworkDraft(),
    val networkHasCredential: Boolean = false,
    val networkApplying: Boolean = false,
    val networkMessage: String? = null,
    val networkError: AppError? = null,
    val commandError: AppError? = null,
    val repositoryError: AppError? = null,
    val query: String = "",
    val results: List<Track> = emptyList(),
    val selected: Track? = null,
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val lyrics: LyricsLookup? = null,
    val loadingLyrics: Boolean = false,
    val loadingSearch: Boolean = false,
    val searchError: AppError? = null,
    val downloads: List<OfflineAsset> = emptyList(),
    val downloadFailures: Map<AssetId, AppError> = emptyMap(),
    val storageUsage: StorageUsageSnapshot = StorageUsageSnapshot(0, emptyMap()),
) {
    val favoriteIds: Set<TrackId> get() = favorites.mapTo(mutableSetOf()) { it.id }
}

/** Editable profile fields only. Passwords never enter a screen state or persisted config. */
data class NetworkDraft(
    val mode: NetworkMode = NetworkMode.SYSTEM,
    val host: String = "",
    val port: String = "",
    val authentication: Boolean = false,
    val username: String = "",
)
