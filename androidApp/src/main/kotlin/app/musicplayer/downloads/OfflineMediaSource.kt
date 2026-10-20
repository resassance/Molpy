package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import app.musicplayer.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local playback uses the public cache API, with no network upstream or cache-hole repair. */
@UnstableApi
internal class OfflineMediaSource(private val cache: Cache) {
    val dataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory { requireNotNull(it.key) { "Missing local cache identity" } }
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(0)

    suspend fun select(asset: OfflineAsset): AppResult<PlayableSource.Local> = withContext(Dispatchers.IO) {
        val valid = try {
            var extent = 0L
            val spans = cache.getCachedSpans(asset.storageKey)
            spans.isNotEmpty() && spans.all { span ->
                val accepted = span.isCached && span.position == extent && span.length > 0 &&
                    span.length <= Long.MAX_VALUE - extent && span.file?.let { it.isFile && it.length() == span.length } == true
                if (accepted) extent += span.length
                accepted
            } && extent > 0 && extent == asset.bytes &&
                (asset.representation.expectedBytes?.let { it == extent } ?: true) &&
                cache.isCached(asset.storageKey, 0, extent) &&
                ContentMetadata.getContentLength(cache.getContentMetadata(asset.storageKey)).let { it == -1L || it == extent }
        } catch (_: Exception) { false }
        if (valid) AppResult.Success(PlayableSource.Local(asset.id, StableCacheIdentity.uri(asset.id),
            asset.representation.mimeType, asset.storageKey, asset.trackSnapshot, candidateId = asset.candidateId))
        else AppResult.Failure(AppError(ErrorCode.INCOMPLETE_MEDIA, "playback.local", false))
    }
}
