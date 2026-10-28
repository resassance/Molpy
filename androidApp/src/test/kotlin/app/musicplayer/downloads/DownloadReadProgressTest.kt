package app.musicplayer.downloads

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import app.musicplayer.model.*
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class DownloadReadProgressTest {
    @Test
    fun resumedProgressUsesFilePositionRatherThanBufferOffset() {
        val repository =
            object : OfflineRepository {
                override suspend fun findAvailable(trackId: TrackId): OfflineAsset? =
                    error("No repository access during read")

                override suspend fun findAsset(id: AssetId): OfflineAsset? =
                    error("No repository access during read")

                override suspend fun listAssets(): List<OfflineAsset> =
                    error("No repository access during read")

                override suspend fun saveMapping(asset: OfflineAsset) =
                    error("No repository access during read")

                override suspend fun removeMapping(id: AssetId) =
                    error("No repository access during read")

                override suspend fun reconcile(existingStorageKeys: Set<String>) =
                    error("No repository access during read")
            }
        val upstream =
            object : DataSource {
                override fun open(dataSpec: DataSpec): Long = error("Already open")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    buffer.fill(1, offset, offset + length)
                    return length
                }

                override fun getUri(): Uri? = null

                override fun addTransferListener(listener: TransferListener) = Unit

                override fun close() = Unit
            }
        var now = 0L
        val tracker = DownloadActivityTracker { now }
        val id = AssetId("resumed")
        tracker.native(id, "owner", DownloadState.DOWNLOADING, 8192, 16384)
        val session = tracker.begin(id, "owner", 8192, 16384)!!
        val source =
            DownloadDataSource.Factory(
                    repository,
                    { error("Already resolved") },
                    { error("Already connected") },
                    activity = tracker,
                )
                .createDataSource()
        // open() needs Android's Looper/Uri; install its runtime state to exercise real read().
        fun field(name: String) =
            source.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field("upstream").set(source, upstream)
        field("offset").setLong(source, 8192L)
        field("activitySession").set(source, id to session)
        try {
            assertEquals(64, source.read(ByteArray(128), 7, 64))
            assertEquals(8256L, tracker.states.value.getValue(id).bytes)
            now += 1000
            assertEquals(32, source.read(ByteArray(128), 3, 32))
            assertEquals(8288L, tracker.states.value.getValue(id).bytes)
        } finally {
            source.close()
        }
    }
}
