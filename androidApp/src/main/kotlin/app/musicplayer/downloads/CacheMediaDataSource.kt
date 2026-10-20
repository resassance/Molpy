package app.musicplayer.downloads

import android.media.MediaDataSource
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.IOException

/** Bounded local snapshot, never remote content length. All access goes through Media3's cache API. */
@UnstableApi
internal class CacheMediaDataSource(
    cache: Cache,
    private val key: String,
    private val stableUri: String,
    private val extent: Long,
    private val checkpoint: () -> Unit,
) : MediaDataSource() {
    private val factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory { spec -> spec.key ?: throw IOException("Missing local key") }
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(0)
    private var source: DataSource? = null
    private var cursor = -1L
    private var closed = false
    private var failed = false

    val hasFailure: Boolean @Synchronized get() = failed

    @Synchronized override fun getSize(): Long = extent

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        try {
            checkpoint()
            if (closed || position < 0 || offset < 0 || size < 0 || offset > buffer.size || size > buffer.size - offset) {
                throw IOException("Invalid local read")
            }
            if (size == 0) return 0
            if (position >= extent) return -1
            if (failed) throw IOException("Local read previously failed")
            if (source == null || cursor != position) {
                closeCursor()
                val opened = factory.createDataSource()
                source = opened // A rejected open still needs close().
                opened.open(DataSpec.Builder().setUri(stableUri).setKey(key)
                    .setPosition(position).setLength(extent - position).build())
                cursor = position
            }
            val count = source!!.read(buffer, offset, minOf(size.toLong(), extent - position).toInt())
            if (count == C.RESULT_END_OF_INPUT || count <= 0) throw IOException("Incomplete local read")
            cursor += count
            return count
        } catch (failure: Exception) {
            failed = true
            // Cancellation is checked again by the worker even if a native parser swallows callbacks.
            throw if (failure is IOException) failure else IOException("Local read failed")
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { closeCursor() } catch (_: Exception) { failed = true; throw IOException("Local close failed") }
    }

    private fun closeCursor() {
        val active = source
        source = null; cursor = -1
        active?.close()
    }
}
