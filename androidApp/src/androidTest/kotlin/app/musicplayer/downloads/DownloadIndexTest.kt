package app.musicplayer.downloads

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DownloadIndexTest {
    @Test fun actualProgressiveDownloadPersistsOnlyStableIdentityAcrossManagerRestart() = runBlocking {
        DownloadTestServers().use { server ->
            val fixture = NativeDownloadFixture()
            val asset = fixture.asset
            val runtime = server.redirectUri
            val repository = FixtureOfflineRepository(asset)
            val upstream = DownloadDataSource.Factory(repository, { AppResult.Success(fixture.lease(runtime)) }, { OkHttpClient() })
            try {
                fixture.open(upstream)
                fixture.download()
                val saved = fixture.index.getDownload(asset.id.value)!!
                assertTrue(saved.request.uri.toString().startsWith("musicplayer-download://"))
                assertEquals(asset.storageKey, saved.request.customCacheKey)
                assertTrue(fixture.cache.cacheSpace > 44)
                assertNull(ContentMetadata.getRedirectedUri(fixture.cache.getContentMetadata(asset.storageKey)))
                fixture.assertNoRuntimeSecrets()
                val key = saved.request.customCacheKey
                fixture.closeManager()
                fixture.open(upstream)
                assertEquals(key, fixture.index.getDownload(asset.id.value)!!.request.customCacheKey)
                fixture.assertNoRuntimeSecrets()
            } finally { fixture.close() }
        }
    }

    @Test fun rejectedNonzeroOpenCannotAppendEvenWhenCacheWriterRetriesUnbounded() = runBlocking {
        DownloadTestServers().use { server ->
            val fixture = NativeDownloadFixture()
            try {
                val repository = FixtureOfflineRepository(fixture.asset)
                var inspections = 0
                val reject = DownloadResponseInspector { inspections++; AppResult.Failure(AppError(ErrorCode.INCOMPATIBLE_RESOURCE, "download.fixture", false)) }
                val factory = DownloadDataSource.Factory(repository, { AppResult.Success(fixture.lease(server.redirectUri)) }, { OkHttpClient() }, reject)
                fixture.open(factory)
                val cacheSource = CacheDataSource.Factory().setCache(fixture.cache).setUpstreamDataSourceFactory(factory).createDataSourceForDownloading()
                val spec = androidx.media3.datasource.DataSpec.Builder().setUri(StableCacheIdentity.uri(fixture.asset.id))
                    .setKey(fixture.asset.storageKey).setPosition(10).setLength(30).build()
                val error = withContext(Dispatchers.IO) { runCatching { CacheWriter(cacheSource, spec, null, null).cache() }.exceptionOrNull() }
                assertNotNull(error)
                assertEquals("Fallback must retain the rejection before second HTTP open", 1, inspections)
                assertEquals(0L, fixture.cache.cacheSpace)
            } finally { fixture.close() }
        }
    }
}

@UnstableApi
internal class NativeDownloadFixture {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "native-download-${UUID.randomUUID()}")
    private val isolated = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name).apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private val source = SourceRef("youtube", "video", "synthetic")
    private val candidate = PlaybackCandidate(CandidateId("fixture"), source, "original", 1000, MatchConfidence.HIGH)
    private val track = Track(TrackId("fixture"), "Generated sine", emptyList(), null, Artwork(ArtworkKind.PLACEHOLDER, null, null), 1000,
        setOf(source), MetadataProvenance(source, 0, false), listOf(candidate))
    val asset = OfflineAsset(AssetId("fixture"), track.id, track, candidate.id, source,
        Representation("wav", "audio/wav", "pcm", null, 1000, null), DownloadPolicy().keyFor(track.id, candidate.id, "wav"), 0, DownloadState.QUEUED)
    fun lease(uri: String, generation: Long = 1) = ResolvedPlayback(candidate.id, asset.representation, TransportKind.PROGRESSIVE,
        MediaRequest(uri, mapOf("X-Fixture" to "runtime-only", "Proxy-Authorization" to "must-not-reach-origin")), null, generation)
    lateinit var database: StandaloneDatabaseProvider
    lateinit var cache: SimpleCache
    lateinit var manager: DownloadManager
    val index get() = manager.downloadIndex
    private val executor = Executors.newSingleThreadExecutor()
    private var opened = false
    suspend fun open(factory: androidx.media3.datasource.DataSource.Factory) {
        database = StandaloneDatabaseProvider(isolated)
        cache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), database)
        withContext(Dispatchers.Main) {
            manager = DownloadManager(isolated, database, cache, factory, executor)
            manager.maxParallelDownloads = 1
            manager.minRetryCount = 0
        }
        opened = true
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { manager.isInitialized }) delay(20) }
    }
    suspend fun download(expectedState: Int = Download.STATE_COMPLETED) {
        val terminal = CompletableDeferred<Download>()
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.request.id == asset.id.value && download.isTerminalState) terminal.complete(download)
            }
        }
        try {
            withContext(Dispatchers.Main) {
                manager.addListener(listener)
                manager.addDownload(DownloadRequest.Builder(asset.id.value, Uri.parse(StableCacheIdentity.uri(asset.id)))
                    .setMimeType(asset.representation.mimeType).setCustomCacheKey(asset.storageKey)
                    .setData(asset.representation.id.toByteArray()).build())
                manager.resumeDownloads()
            }
            assertEquals("Actual Media3 transfer terminal state", expectedState, withTimeout(15_000) { terminal.await() }.state)
        } finally { withContext(Dispatchers.Main) { manager.removeListener(listener) } }
    }
    fun assertNoRuntimeSecrets() {
        // Read actual SQLite fields and serialized cache metadata, rather than adapter mocks.
        val text = buildString {
            index.getDownloads().use { cursor -> while (cursor.moveToNext()) {
                val request = cursor.download.request
                append(request.uri); append(request.customCacheKey); append(request.data.toString(Charsets.UTF_8))
            } }
            database.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { tables ->
                while (tables.moveToNext()) {
                    val name = tables.getString(0)
                    if (!name.startsWith("ExoPlayer")) continue
                    database.readableDatabase.rawQuery("SELECT * FROM \"$name\"", null).use { rows ->
                        while (rows.moveToNext()) for (column in 0 until rows.columnCount) {
                            if (rows.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB) append(rows.getBlob(column).toString(Charsets.ISO_8859_1))
                            else append(rows.getString(column))
                        }
                    }
                }
            }
        }
        assertFalse(text.contains("signature=test-secret"))
        assertFalse(text.contains("Proxy-Authorization", true))
        assertFalse(text.contains("runtime-only"))
    }
    suspend fun closeManager() {
        if (!opened) return
        val idle = CompletableDeferred<Unit>()
        val listener = object : DownloadManager.Listener {
            override fun onIdle(downloadManager: DownloadManager) {
                // Pinned isIdle means activeTaskCount == 0 and pendingMessages == 0.
                if (downloadManager.isIdle) idle.complete(Unit)
            }
        }
        try {
            withContext(Dispatchers.Main) {
                manager.addListener(listener)
                manager.pauseDownloads()
                // pauseDownloads may be a no-op when already paused. This guaranteed queued
                // command for an absent ID gives a fresh post-command onIdle without a new row.
                manager.setStopReason("fixture-idle-${UUID.randomUUID()}", 1)
            }
            withTimeout(10_000) { idle.await() }
        } finally { withContext(Dispatchers.Main) { manager.removeListener(listener) } }
        // A timeout above deliberately leaves manager/cache/database open: release is no join.
        withContext(Dispatchers.Main) { manager.release() }
        withContext(Dispatchers.IO) { cache.release(); database.close() }
        opened = false
    }
    suspend fun close() { closeManager(); executor.shutdownNow(); root.deleteRecursively() }
}

internal class FixtureOfflineRepository(asset: OfflineAsset) : OfflineRepository {
    private val assets = mutableMapOf(asset.id to asset)
    override suspend fun findAvailable(trackId: TrackId) = assets.values.firstOrNull { it.trackId == trackId && it.state == DownloadState.AVAILABLE }
    override suspend fun findAsset(id: AssetId) = assets[id]
    override suspend fun listAssets() = assets.values.toList()
    override suspend fun saveMapping(asset: OfflineAsset) { assets[asset.id] = asset }
    override suspend fun removeMapping(id: AssetId) { assets.remove(id) }
    override suspend fun reconcile(existingStorageKeys: Set<String>) { assets.entries.removeAll { it.value.state == DownloadState.AVAILABLE && it.value.storageKey !in existingStorageKeys } }
}
