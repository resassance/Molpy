package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Counts bytes through cache ownership and ancillary files, never interprets Media3 span files. */
@UnstableApi
internal class StorageUsage(
    private val cache: Cache,
    private val repository: OfflineRepository,
    private val resources: OfflineResources,
) {
    suspend fun measure(): StorageUsageSnapshot = withContext(Dispatchers.IO) {
        val assets = repository.listAssets()
        val perAsset = assets.associate { asset ->
            val mediaBytes = cache.getCachedSpans(asset.storageKey).sumOf { span ->
                span.file?.takeIf { it.isFile }?.length()?.coerceAtMost(span.length)?.coerceAtLeast(0) ?: 0L
            }
            val artworkBytes = asset.trackSnapshot.artwork.localKey?.let(resources::ownedFile)?.takeIf { it.isFile }?.length() ?: 0L
            asset.id to (mediaBytes + artworkBytes)
        }
        val resourceBytes = resources.directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        StorageUsageSnapshot(cache.cacheSpace.coerceAtLeast(0) + resourceBytes, perAsset)
    }
}
