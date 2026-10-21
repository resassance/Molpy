package app.musicplayer.downloads

import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.OsConstants
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Media3 cache/index/server and production owner. Requires Android; host compilation is not
 * execution.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class InterruptedDownloadTest {
    @Test
    fun interruptedTransferWithChangedEtagRestartsIntoExactlyOneWholeRepresentation() =
        runBlocking {
            recover(ignoresRange = false)
        }

    @Test
    fun interruptedTransferWhenServerIgnoresRangeRestartsBeforeAnyNewBytes() = runBlocking {
        recover(ignoresRange = true)
    }

    private suspend fun recover(ignoresRange: Boolean) {
        RecoveryServer().use { server ->
            val fixture = RecoveryFixture.create(server)
            try {
                fixture.start()
                fixture.await(DownloadState.FAILED)
                assertTrue(
                    fixture.store.cache.getCachedSpans(fixture.asset.storageKey).isNotEmpty()
                )
                val old = fixture.store.request(fixture.asset).data.toString(Charsets.UTF_8)
                server.replace(ignoresRange)
                fixture.start()
                fixture.await(DownloadState.AVAILABLE)
                assertTrue(
                    "A real Range attempt must reach admission",
                    server.ranges.any { it > 0 },
                )
                assertTrue(
                    "Owner retry starts a fresh writer at zero",
                    server.ranges.count { it == 0L } >= 2,
                )
                assertArrayEquals(server.audio, fixture.readAll())
                assertEquals(
                    "\"second\"",
                    ResourceStampMetadata.read(fixture.store.cache, fixture.asset.storageKey)
                        ?.strongValidator,
                )
                assertNotEquals(
                    old,
                    fixture.store.manager.downloadIndex
                        .getDownload(fixture.asset.id.value)!!
                        .request
                        .data
                        .toString(Charsets.UTF_8),
                )
                // A previously completed callback cannot relabel or demote the admitted current
                // attempt.
                val staleRequest =
                    fixture.store.request(fixture.asset).copyWithId(fixture.asset.id.value)
                val current =
                    fixture.store.manager.downloadIndex.getDownload(fixture.asset.id.value)!!
                val stale =
                    Download(
                        staleRequest.buildUponData(old),
                        Download.STATE_COMPLETED,
                        current.startTimeMs,
                        current.updateTimeMs,
                        current.contentLength,
                        0,
                        0,
                    )
                fixture.store.mutex.withLock { fixture.store.project(stale, null) }
                assertEquals(
                    DownloadState.AVAILABLE,
                    fixture.repository.findAsset(fixture.asset.id)!!.state,
                )
                fixture.assertStableMetadata()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun unchangedStrongValidatorResumesActualCachedPrefixWithoutReset() = runBlocking {
        RecoveryServer().use { server ->
            val fixture = RecoveryFixture.create(server)
            try {
                fixture.start()
                fixture.await(DownloadState.FAILED)
                val token = fixture.store.request(fixture.asset).data
                fixture.start()
                fixture.await(DownloadState.AVAILABLE)
                assertTrue(server.ranges.any { it > 0 })
                assertEquals(1, server.ranges.count { it == 0L })
                assertArrayEquals(
                    token,
                    fixture.store.manager.downloadIndex
                        .getDownload(fixture.asset.id.value)!!
                        .request
                        .data,
                )
                assertArrayEquals(server.audio, fixture.readAll())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun boundedCacheWriterFallbackRetainsOneStickyRejectionBeforeAnySinkBytes() = runBlocking {
        RecoveryServer().use { server ->
            server.completeInitially()
            val fixture = RecoveryFixture.create(server)
            try {
                var notifications = 0
                val recovery =
                    DownloadRecovery(fixture.store.cache) { _, _, _, _ -> notifications++ }
                val upstream =
                    DownloadDataSource.Factory(
                        fixture.repository,
                        {
                            AppResult.Success(
                                ResolvedPlayback(
                                    fixture.asset.candidateId,
                                    fixture.asset.representation,
                                    TransportKind.PROGRESSIVE,
                                    MediaRequest(server.uri, emptyMap()),
                                    null,
                                    1,
                                )
                            )
                        },
                        { OkHttpClient() },
                        recovery = recovery,
                    )
                val cacheSource =
                    CacheDataSource.Factory()
                        .setCache(fixture.store.cache)
                        .setUpstreamDataSourceFactory(upstream)
                        .createDataSourceForDownloading()
                val spec =
                    DataSpec.Builder()
                        .setUri(StableCacheIdentity.uri(fixture.asset.id))
                        .setKey(fixture.asset.storageKey)
                        .setPosition(10)
                        .setLength(30)
                        .build()
                val failure =
                    withContext(Dispatchers.IO) {
                        runCatching { CacheWriter(cacheSource, spec, null, null).cache() }
                            .exceptionOrNull()
                    }
                assertNotNull(failure)
                assertEquals(1, notifications)
                assertEquals(1, server.ranges.size)
                assertEquals(0L, fixture.store.cache.cacheSpace)
                assertFalse(
                    ResourceStampMetadata.hasOwned(fixture.store.cache, fixture.asset.storageKey)
                )
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun changedImmutableRepresentationCleansPartialAndRetainsFailedRecordingSnapshot() =
        runBlocking {
            RecoveryServer().use { server ->
                val fixture = RecoveryFixture.create(server)
                try {
                    fixture.start()
                    fixture.await(DownloadState.FAILED)
                    fixture.resolvedRepresentation =
                        fixture.asset.representation.copy(id = "replacement")
                    fixture.start()
                    withTimeout(15_000) {
                        while (
                            fixture.asset.id in fixture.store.removing ||
                                fixture.store.manager.downloadIndex.getDownload(
                                    fixture.asset.id.value
                                ) != null
                        ) delay(20)
                    }
                    val saved = fixture.repository.findAsset(fixture.asset.id)!!
                    assertEquals(fixture.asset.trackSnapshot, saved.trackSnapshot)
                    assertEquals(fixture.asset.representation, saved.representation)
                    assertEquals(DownloadState.FAILED, saved.state)
                    assertEquals(
                        ErrorCode.RESOURCE_CHANGED,
                        fixture.store.failures.value[fixture.asset.id]?.code,
                    )
                    assertEquals(
                        0L,
                        fixture.store.cache.getCachedBytes(saved.storageKey, 0, Long.MAX_VALUE),
                    )
                    assertFalse(
                        ResourceStampMetadata.hasOwned(fixture.store.cache, saved.storageKey)
                    )
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun cacheCommitStorageFullFailureNeverPublishesAvailableAndPreservesUnrelatedReadyAudio() =
        runBlocking {
            RecoveryServer().use { server ->
                server.completeInitially()
                val fixture = RecoveryFixture.create(server)
                try {
                    fixture.start()
                    fixture.await(DownloadState.AVAILABLE)
                    val readyBytes = fixture.readAll()
                    val other =
                        fixture.asset.copy(
                            id = AssetId("other"),
                            trackId = TrackId("other"),
                            trackSnapshot = fixture.asset.trackSnapshot.copy(id = TrackId("other")),
                            storageKey =
                                DownloadPolicy()
                                    .keyFor(
                                        TrackId("other"),
                                        fixture.asset.candidateId,
                                        fixture.asset.representation.id,
                                    ),
                        )
                    fixture.repository.saveMapping(other)
                    val full =
                        object : Cache.Listener {
                            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                                // Inject the actual platform ENOSPC type at a real cache commit
                                // boundary.
                                // This is deterministic fault injection, not exhaustion of the
                                // device disk.
                                throw ErrnoException("fixture-cache-commit", OsConstants.ENOSPC)
                            }

                            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

                            override fun onSpanTouched(
                                cache: Cache,
                                oldSpan: CacheSpan,
                                newSpan: CacheSpan,
                            ) = Unit
                        }
                    fixture.store.cache.addListener(other.storageKey, full)
                    try {
                        fixture.start(other)
                        fixture.await(DownloadState.FAILED, other.id)
                        assertEquals(
                            ErrorCode.STORAGE_FULL,
                            fixture.store.failures.value[other.id]?.code,
                        )
                        assertNotEquals(
                            DownloadState.AVAILABLE,
                            fixture.repository.findAsset(other.id)!!.state,
                        )
                        assertEquals(
                            DownloadState.AVAILABLE,
                            fixture.repository.findAsset(fixture.asset.id)!!.state,
                        )
                        assertArrayEquals(readyBytes, fixture.readAll())
                    } finally {
                        fixture.store.cache.removeListener(other.storageKey, full)
                    }
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun readableShortHttp200CannotPublishAvailableThroughTheRealOwner() = runBlocking {
        RecoveryServer().use { server ->
            server.completeInitially()
            val fixture = RecoveryFixture.create(server, 120_000)
            try {
                fixture.start()
                fixture.await(DownloadState.FAILED)
                assertEquals(
                    Download.STATE_COMPLETED,
                    fixture.store.manager.downloadIndex.getDownload(fixture.asset.id.value)!!.state,
                )
                assertEquals(
                    ErrorCode.INCOMPLETE_MEDIA,
                    fixture.store.failures.value[fixture.asset.id]?.code,
                )
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun metadataOnlyResetClearsEveryOwnedAndMedia3Field() = runBlocking {
        RecoveryServer().use { server ->
            val fixture = RecoveryFixture.create(server)
            try {
                val cache = fixture.store.cache
                val key = fixture.asset.storageKey
                ResourceStampMetadata.write(
                    cache,
                    key,
                    ResourceStamp(fixture.asset.source, "wav", "\"old\"", 100),
                )
                cache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations()
                        .set(ContentMetadata.KEY_CONTENT_LENGTH, 100L)
                        .set(ContentMetadata.KEY_REDIRECTED_URI, "runtime-secret"),
                )
                fixture.store.mutex.withLock {
                    fixture.store.finishRemoval(fixture.asset, delete = false)
                }
                assertFalse(ResourceStampMetadata.hasOwned(cache, key))
                assertFalse(
                    cache.getContentMetadata(key).contains(ContentMetadata.KEY_CONTENT_LENGTH)
                )
                assertFalse(
                    cache.getContentMetadata(key).contains(ContentMetadata.KEY_REDIRECTED_URI)
                )
                assertTrue(cache.getCachedSpans(key).isEmpty())
            } finally {
                fixture.close()
            }
        }
    }
}

// Keep request fixture changes in tests; production persists only opaque attempt IDs and stable
// identity.
private fun androidx.media3.exoplayer.offline.DownloadRequest.buildUponData(data: String) =
    androidx.media3.exoplayer.offline.DownloadRequest.Builder(id, uri)
        .setMimeType(mimeType)
        .setCustomCacheKey(customCacheKey)
        .setData(data.toByteArray(Charsets.UTF_8))
        .build()

@UnstableApi
private class RecoveryFixture
private constructor(private val server: RecoveryServer, duration: Long) {
    private val root =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "recovery-${UUID.randomUUID()}",
        )
    private val context =
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir() = root

            override fun getDatabasePath(name: String) =
                File(root, name).apply { parentFile!!.mkdirs() }

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
            ) =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                    getDatabasePath(name),
                    factory,
                )

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                handler: android.database.DatabaseErrorHandler?,
            ) =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                    getDatabasePath(name).path,
                    factory,
                    handler,
                )
        }
    private val source = SourceRef("fixture", "audio", "generated")
    private val candidate =
        PlaybackCandidate(
            CandidateId("generated"),
            source,
            "original",
            duration,
            MatchConfidence.HIGH,
        )
    private val track =
        Track(
            TrackId("generated"),
            "Generated sine",
            emptyList(),
            null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null),
            duration,
            setOf(source),
            MetadataProvenance(source, 0, false),
            listOf(candidate),
        )
    val asset =
        OfflineAsset(
            AssetId("generated"),
            track.id,
            track,
            candidate.id,
            source,
            Representation("wav", "audio/wav", "pcm", null, duration, null),
            DownloadPolicy().keyFor(track.id, candidate.id, "wav"),
            0,
            DownloadState.QUEUED,
        )
    @Volatile var resolvedRepresentation = asset.representation
    val repository = RecoveryRepository(asset)
    lateinit var store: DownloadStore

    private suspend fun open() {
        withContext(Dispatchers.Main) {
            store =
                DownloadStore(
                    context,
                    repository,
                    { _, _ ->
                        AppResult.Success(
                            ResolvedPlayback(
                                candidate.id,
                                resolvedRepresentation,
                                TransportKind.PROGRESSIVE,
                                MediaRequest(server.uri + "?signature=runtime-secret", emptyMap()),
                                null,
                                1,
                            )
                        )
                    },
                    {
                        NetworkContext(1, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
                    },
                    { OkHttpClient() },
                    { throw IllegalStateException("Signal must be isolated") },
                )
            store.manager.minRetryCount = 0
        }
        store.startup.await()
    }

    suspend fun start(asset: OfflineAsset = this.asset) {
        store.mutex.withLock {
            store.clearFailure(asset.id)
            repository.saveMapping(
                (repository.findAsset(asset.id) ?: asset).copy(state = DownloadState.QUEUED)
            )
            withContext(Dispatchers.Main) {
                store.manager.addDownload(store.request(asset))
                store.manager.resumeDownloads()
            }
        }
    }

    suspend fun await(state: DownloadState, id: AssetId = asset.id) =
        withTimeout(20_000) {
            while (
                !store.mutex.withLock {
                    repository.findAsset(id)?.state == state &&
                        (state != DownloadState.FAILED ||
                            store.manager.downloadIndex.getDownload(id.value)?.state in
                                setOf(Download.STATE_FAILED, Download.STATE_COMPLETED))
                }
            ) delay(20)
        }

    fun readAll(): ByteArray {
        val source =
            CacheDataSource.Factory()
                .setCache(store.cache)
                .setUpstreamDataSourceFactory(null)
                .setCacheWriteDataSinkFactory(null)
                .createDataSource()
        return try {
            source.open(
                DataSpec.Builder()
                    .setUri(StableCacheIdentity.uri(asset.id))
                    .setKey(asset.storageKey)
                    .build()
            )
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == -1) break
                check(count > 0)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            source.close()
        }
    }

    fun assertStableMetadata() {
        store.manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val request = cursor.download.request
                assertFalse(request.uri.toString().contains("runtime-secret"))
                assertFalse(request.data.toString(Charsets.UTF_8).contains("runtime-secret"))
            }
        }
        assertFalse(
            store.cache
                .getContentMetadata(asset.storageKey)
                .contains(ContentMetadata.KEY_REDIRECTED_URI)
        )
    }

    suspend fun close() {
        val idle = CompletableDeferred<Unit>()
        val listener =
            object : DownloadManager.Listener {
                override fun onIdle(downloadManager: DownloadManager) {
                    if (downloadManager.isIdle) idle.complete(Unit)
                }
            }
        try {
            withContext(Dispatchers.Main) {
                store.manager.addListener(listener)
                store.manager.pauseDownloads()
                store.manager.setStopReason("fresh-idle-${UUID.randomUUID()}", 1)
            }
            withTimeout(15_000) { idle.await() }
        } finally {
            withContext(Dispatchers.Main) { store.manager.removeListener(listener) }
        }
        // Timeout leaves owner/cache intact: release is not a writer join.
        store.scope.cancel()
        withContext(Dispatchers.Main) { store.manager.release() }
        withContext(Dispatchers.IO) {
            store.cache.release()
            store.database.close()
            root.deleteRecursively()
        }
    }

    companion object {
        suspend fun create(server: RecoveryServer, duration: Long = 1000) =
            RecoveryFixture(server, duration).also { it.open() }
    }
}

private class RecoveryRepository(asset: OfflineAsset) : OfflineRepository {
    private val assets = ConcurrentHashMap<AssetId, OfflineAsset>().apply { put(asset.id, asset) }

    override suspend fun findAvailable(trackId: TrackId) =
        assets.values.firstOrNull { it.trackId == trackId && it.state == DownloadState.AVAILABLE }

    override suspend fun findAsset(id: AssetId) = assets[id]

    override suspend fun listAssets() = assets.values.toList()

    override suspend fun saveMapping(asset: OfflineAsset) {
        assets[asset.id] = asset
    }

    override suspend fun removeMapping(id: AssetId) {
        assets.remove(id)
    }

    override suspend fun reconcile(existingStorageKeys: Set<String>) = Unit
}

private class RecoveryServer : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool {
        Thread(it, "interrupted-fixture").apply { isDaemon = true }
    }
    private val sockets = CopyOnWriteArrayList<java.net.Socket>()
    val ranges = CopyOnWriteArrayList<Long>()
    @Volatile private var interrupt = true
    @Volatile private var ignoreRange = false
    @Volatile private var etag = "\"first\""
    @Volatile
    var audio = wav(440)
        private set

    val uri = "http://127.0.0.1:${server.localPort}/audio"

    init {
        pool.submit {
            while (!server.isClosed) try {
                val socket = server.accept()
                sockets += socket
                pool.submit {
                    socket.use {
                        runCatching {
                            socket.soTimeout = 8000
                            val input = socket.getInputStream()
                            val head = ByteArrayOutputStream()
                            var tail = 0
                            while (head.size() < 32768) {
                                val next = input.read()
                                if (next < 0) break
                                head.write(next)
                                tail = (tail shl 8) or next
                                if (tail == 0x0d0a0d0a) break
                            }
                            val request = head.toString("ISO-8859-1")
                            val requested =
                                Regex("(?im)^Range: bytes=([0-9]+)-")
                                    .find(request)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toLong() ?: 0
                            ranges += requested
                            val start = if (ignoreRange) 0 else requested.toInt()
                            val body = audio
                            val count = body.size - start
                            val range =
                                if (start > 0)
                                    "Content-Range: bytes $start-${body.lastIndex}/${body.size}\r\n"
                                else ""
                            val status = if (start > 0) "206 Partial Content" else "200 OK"
                            val output = socket.getOutputStream()
                            output.write(
                                "HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nETag: $etag\r\nContent-Length: $count\r\n${range}Connection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            if (interrupt) {
                                interrupt = false
                                output.write(body, start, minOf(8192, count))
                                output.flush()
                            } else {
                                output.write(body, start, count)
                                output.flush()
                            }
                        }
                    }
                    sockets -= socket
                }
            } catch (_: java.io.IOException) {
                break
            }
        }
    }

    fun completeInitially() {
        interrupt = false
    }

    fun replace(ignore: Boolean) {
        audio = wav(880)
        etag = "\"second\""
        ignoreRange = ignore
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun wav(frequency: Int) =
        ByteBuffer.allocate(32044)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(capacity() - 8)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(16000)
                putInt(32000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(capacity() - 44)
                repeat(16000) {
                    putShort((sin(it * 2 * Math.PI * frequency / 16000) * 2000).toInt().toShort())
                }
            }
            .array()
}
