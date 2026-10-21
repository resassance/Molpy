package app.musicplayer.downloads

import app.musicplayer.model.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.*

/** Public Media3 identity is local and canonical; it never contains an upstream request. */
internal object StableCacheIdentity {
    fun uri(assetId: AssetId): String =
        PREFIX + URLEncoder.encode(assetId.value, "UTF-8").replace("+", "%20").replace("%7E", "~")

    fun assetId(uri: String): AssetId {
        val parsed = URI(uri)
        require(
            parsed.scheme == "musicplayer-download" &&
                parsed.rawAuthority == "asset" &&
                parsed.rawQuery == null &&
                parsed.rawFragment == null &&
                parsed.rawPath.startsWith("/")
        )
        val id = AssetId(URLDecoder.decode(parsed.rawPath.substring(1), "UTF-8"))
        require(uri(id) == uri) { "Noncanonical download identity" }
        return id
    }

    private const val PREFIX = "musicplayer-download://asset/"
}

/** Media3's persisted transfer states. Completion is unverified, so defaults to FAILED. */
internal object DownloadProjection {
    fun state(media3State: Int): DownloadState =
        when (media3State) {
            0,
            7 -> DownloadState.QUEUED
            2 -> DownloadState.DOWNLOADING
            1,
            5 -> DownloadState.PAUSED
            else -> DownloadState.FAILED
        }
}

/** Admission happens before a durable request is created. Only one progressive representation. */
internal object DownloadCommandPolicy {
    fun admit(lease: ResolvedPlayback, candidate: PlaybackCandidate, generation: Long): AppError? =
        when {
            lease.transport != TransportKind.PROGRESSIVE ||
                !isProgressiveAudio(lease.representation.mimeType) ->
                AppError(ErrorCode.UNSUPPORTED_TRANSPORT, "download.command", false)
            lease.candidateId != candidate.id || lease.networkGeneration != generation ->
                AppError(ErrorCode.RESOURCE_CHANGED, "download.command", false)
            else -> null
        }
}

/** Recovery and removal affect only their asset; the map contains safe values only. */
internal class DownloadFailureState {
    private val state = MutableStateFlow<Map<AssetId, AppError>>(emptyMap())
    val values = state.asStateFlow()

    fun record(id: AssetId, error: AppError) {
        state.update { it + (id to error) }
    }

    fun clear(id: AssetId) {
        state.update { it - id }
    }
}
