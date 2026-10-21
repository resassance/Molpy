package app.musicplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.musicplayer.model.*
import okhttp3.Call

/** Per-lease factories keep request headers out of subsequent tracks and persistent caches. */
@UnstableApi
internal class MediaSourceFactory(
    private val callFactoryFor: (Long) -> Call.Factory,
    private val localDataSource: DataSource.Factory? = null,
) {
    constructor(http: Call.Factory) : this({ http })

    fun create(entry: QueueEntry, source: PlayableSource): AppResult<MediaSource> {
        if (
            source is PlayableSource.Remote &&
                source.lease.transport !in PlaybackCoordinator.CAPABILITIES.transports ||
                source is PlayableSource.Local && localDataSource == null
        ) {
            return AppResult.Failure(
                AppError(ErrorCode.UNSUPPORTED_TRANSPORT, "player.source", false)
            )
        }
        val metadata =
            MediaMetadata.Builder()
                .setTitle(source.track.title)
                .setArtist(source.track.artists.joinToString(", ") { it.name })
                .setAlbumTitle(source.track.album?.name)
                .build()
        val item =
            MediaItem.Builder()
                .setTag(
                    when (source) {
                        is PlayableSource.Local -> source.loadId
                        is PlayableSource.Remote -> source.loadId
                    }
                )
                .setMediaId(entry.id.value)
                .setUri(
                    when (source) {
                        is PlayableSource.Local -> source.stableUri
                        is PlayableSource.Remote -> source.lease.request.uri
                    }
                )
                .setCustomCacheKey((source as? PlayableSource.Local)?.cacheKey)
                .setMimeType(
                    when (source) {
                        is PlayableSource.Local -> source.mimeType
                        is PlayableSource.Remote ->
                            media3MimeType(
                                source.lease.transport,
                                source.lease.representation.mimeType,
                            )
                    }
                )
                .setMediaMetadata(metadata)
                .build()
        val dataSource =
            when (source) {
                is PlayableSource.Local -> requireNotNull(localDataSource)
                is PlayableSource.Remote ->
                    OkHttpDataSource.Factory(callFactoryFor(source.lease.networkGeneration))
                        .setDefaultRequestProperties(source.lease.request.headers)
            }
        return AppResult.Success(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
    }
}
