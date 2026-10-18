package app.musicplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.musicplayer.model.*
import okhttp3.OkHttpClient

/** Per-lease factories keep request headers out of subsequent tracks and persistent caches. */
@UnstableApi
internal class MediaSourceFactory(private val http: OkHttpClient) {
    fun create(entry: QueueEntry, source: PlayableSource): AppResult<MediaSource> {
        if (source !is PlayableSource.Remote || source.lease.transport !in PlaybackCoordinator.CAPABILITIES.transports) {
            return AppResult.Failure(AppError(ErrorCode.UNSUPPORTED_TRANSPORT, "player.source", false))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(source.track.title)
            .setArtist(source.track.artists.joinToString(", ") { it.name })
            .setAlbumTitle(source.track.album?.name)
            .setArtworkUri(source.track.artwork.remoteUrl?.let(Uri::parse))
            .build()
        val item = MediaItem.Builder()
            .setMediaId(entry.id.value)
            .setUri(source.lease.request.uri)
            .setMimeType(media3MimeType(source.lease.transport, source.lease.representation.mimeType))
            .setMediaMetadata(metadata)
            .build()
        val dataSource = OkHttpDataSource.Factory(http).setDefaultRequestProperties(source.lease.request.headers)
        return AppResult.Success(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
    }
}
