package app.musicplayer.playback

import app.musicplayer.downloads.*
import app.musicplayer.library.AudioMetadataRepository
import app.musicplayer.model.*
import kotlinx.coroutines.*

/**
 * A ready recording is independent of provider/network validity. A broken local asset fails closed.
 */
class PlaybackSourceSelector(
    private val resolver: PlaybackResolver,
    private val network: () -> NetworkContext,
    private val offline: OfflineRepository? = null,
    private val localSource: suspend (OfflineAsset) -> AppResult<PlayableSource.Local> = {
        AppResult.Failure(AppError(ErrorCode.INCOMPLETE_MEDIA, "playback.local", false))
    },
    private val awaitOffline: suspend () -> Unit = {},
    private val invalidateLocal: suspend (AssetId) -> Unit = { id ->
        offline
            ?.findAsset(id)
            ?.takeIf { it.state == DownloadState.AVAILABLE }
            ?.let { offline.saveMapping(it.copy(state = DownloadState.FAILED)) }
    },
    private val audioMetadata: AudioMetadataRepository? = null,
    private val metadataFailure: (AppError) -> Unit = {},
) {
    suspend fun select(track: Track): AppResult<PlayableSource> = select(track, { true }) {}

    /**
     * Called immediately before an actual remote resolve, so the owner retains its attempt budget.
     */
    internal suspend fun select(
        track: Track,
        remoteAllowed: () -> Boolean,
        resolvingRemote: () -> Unit,
    ): AppResult<PlayableSource> {
        awaitOffline()
        val asset = offline?.findAvailable(track.id)
        if (asset != null) {
            return when (val local = localSource(asset)) {
                is AppResult.Success -> {
                    val metadata =
                        try {
                            withTimeoutOrNull(200) {
                                audioMetadata?.get(
                                    AudioMetadataKey(
                                        asset.candidateId,
                                        asset.source,
                                        asset.representation.id,
                                    )
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            metadataFailure(
                                AppError(ErrorCode.DATABASE, "playback.audio.metadata", true)
                            )
                            null
                        }
                    AppResult.Success(
                        local.value.copy(
                            track = asset.trackSnapshot,
                            candidateId = asset.candidateId,
                            audioMetadata = metadata,
                        )
                    )
                }
                is AppResult.Failure -> {
                    localFailed(asset.id)
                    AppResult.Failure(AppError(ErrorCode.INCOMPLETE_MEDIA, "playback.local", false))
                }
            }
        }
        val route = network()
        if (!route.ready || !route.online)
            return AppResult.Failure(AppError(ErrorCode.NETWORK, "playback.route", true))
        if (!remoteAllowed())
            return AppResult.Failure(AppError(ErrorCode.NETWORK, "playback.reconnect", true))
        val candidate =
            track.candidates.firstOrNull()
                ?: return AppResult.Failure(
                    AppError(ErrorCode.SOURCE_UNAVAILABLE, "playback.select", false)
                )
        resolvingRemote()
        return when (
            val resolved = resolver.resolve(candidate, PlaybackCoordinator.CAPABILITIES, route)
        ) {
            is AppResult.Success -> AppResult.Success(PlayableSource.Remote(resolved.value, track))
            is AppResult.Failure -> resolved
        }
    }

    suspend fun localFailed(id: AssetId) = invalidateLocal(id)
}
