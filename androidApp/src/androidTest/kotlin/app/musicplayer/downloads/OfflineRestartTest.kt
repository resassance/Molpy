package app.musicplayer.downloads

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import app.musicplayer.playback.*
import app.musicplayer.queue.QueueController
import app.musicplayer.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** Actual Room, DownloadStore, persistent Media3 index/cache and native verifier; Android execution required. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineRestartTest {
    @Test fun reopenedOwnerReverifiesCompleteRejectsPartialAndMissingWithoutPersistingRuntimeAddresses() = runBlocking {
        RestartOrigin().use { origin ->
            val fixture = RestartFixture.create(origin)
            try {
                fixture.download(fixture.complete, DownloadState.AVAILABLE)
                fixture.download(fixture.partial, DownloadState.FAILED)
                fixture.download(fixture.missing, DownloadState.AVAILABLE)
                val prefix = fixture.store.cache.getCachedBytes(fixture.partial.storageKey, 0, Long.MAX_VALUE)
                assertTrue("Truncated HTTP body must leave an actual incomplete cache prefix", prefix in 1 until origin.audio.size.toLong())
                assertTrue("Marker was supplied to the actual HTTP producer", origin.requests.any { it.contains("signature=test-secret") })
                assertEquals(3, fixture.resolveCalls.get())
                fixture.assertStablePersistentValues()
                fixture.closeStorage()
                fixture.assertDiskHasNoRuntimeAddresses()
                // Simulate loss while the original owner and all its writers are closed.
                fixture.removeClosedCacheKey(fixture.missing.storageKey)
                val requestsBeforeReopen = origin.requests.size
                fixture.open(allowResolve = false)

                assertEquals(DownloadState.AVAILABLE, fixture.repository.findAsset(fixture.complete.id)!!.state)
                assertNotEquals(DownloadState.AVAILABLE, fixture.repository.findAsset(fixture.partial.id)!!.state)
                assertNull(fixture.repository.findAvailable(fixture.missing.trackId))
                assertNotNull("Missing audio must retain Track metadata", fixture.repository.getTrack(fixture.missing.trackId))
                assertEquals(Download.STATE_COMPLETED, fixture.store.manager.downloadIndex.getDownload(fixture.complete.id.value)!!.state)
                assertEquals(Download.STATE_FAILED, fixture.store.manager.downloadIndex.getDownload(fixture.partial.id.value)!!.state)
                assertTrue(OfflineMediaSource(fixture.store.cache).select(fixture.repository.findAvailable(fixture.complete.trackId)!!) is AppResult.Success)
                assertEquals("Startup must not fresh-resolve transfers", 3, fixture.resolveCalls.get())
                assertEquals("Startup must not perform HTTP", requestsBeforeReopen, origin.requests.size)
                fixture.assertStablePersistentValues()
                fixture.closeStorage()
                fixture.assertDiskHasNoRuntimeAddresses()
            } finally { fixture.close() }
        }
    }

    @Test fun freshQueueRestoreFromRoomStaysPausedAndResolvesOnlyAfterExplicitPlay() = runBlocking {
        RestartOrigin().use { origin ->
            val fixture = RestartFixture.create(origin)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var coordinator: PlaybackCoordinator? = null
            try {
                val entry = QueueEntry(QueueEntryId("restored-occurrence"), fixture.partial.trackId)
                fixture.repository.saveQueue(QueueSnapshot(listOf(entry), entry.id, 250, true))
                fixture.closeStorage()
                fixture.open(allowResolve = false)
                val restored = fixture.repository.restoreQueue()
                assertFalse(restored.playWhenReady)
                assertEquals(entry.id, restored.currentEntryId)
                assertEquals(250L, restored.positionMs)
                val playbackResolves = AtomicInteger()
                val resolver = object : PlaybackResolver {
                    override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> {
                        playbackResolves.incrementAndGet()
                        return AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "fixture.explicit-play", false))
                    }
                }
                withContext(Dispatchers.Main) {
                    val engine = AndroidPlayerEngine(fixture.context, { error("No HTTP before successful resolution") })
                    coordinator = PlaybackCoordinator(engine, resolver, { fixture.route }, scope)
                    val queue = QueueController(engine, coordinator!!, fixture.repository::getTrack)
                    queue.restore(restored)
                    assertFalse(queue.state.value.playWhenReady)
                    assertEquals(PlayerStatus.PAUSED, queue.snapshot.value.status)
                    assertEquals(fixture.partial.trackSnapshot, queue.snapshot.value.track)
                    assertNull("Fresh engine must not load restored media", engine.snapshot.value.current)
                    assertEquals(0, playbackResolves.get())
                    assertEquals(0, fixture.resolveCalls.get())
                    assertTrue(origin.requests.isEmpty())
                    queue.play()
                    assertEquals("Only explicit Play admits a fresh resolver call", 1, playbackResolves.get())
                    assertEquals(ErrorCode.SOURCE_UNAVAILABLE, queue.snapshot.value.error?.code)
                }
            } finally {
                withContext(Dispatchers.Main) { coordinator?.release() }
                scope.coroutineContext.job.cancelAndJoin()
                fixture.close()
            }
        }
    }
}

@UnstableApi
private class RestartFixture(private val origin: RestartOrigin) {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.noBackupFilesDir, "offline-restart-${UUID.randomUUID()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = root
        override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    val route = NetworkContext(1, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
    val resolveCalls = AtomicInteger()
    val complete = asset("complete")
    val partial = asset("partial")
    val missing = asset("missing")
    private var musicDatabase: MusicDatabase? = null
    lateinit var repository: RoomLibraryRepository
        private set
    lateinit var store: DownloadStore
        private set
    private var storageOpen = false

    suspend fun open(allowResolve: Boolean, seed: Boolean = false) {
        check(!storageOpen)
        val database = createMusicDatabase(context)
        musicDatabase = database
        repository = RoomLibraryRepository(database) { 0L }
        if (seed) listOf(complete, partial, missing).forEach { repository.saveMapping(it) }
        withContext(Dispatchers.Main) {
            store = DownloadStore(context, repository, { candidate, _ ->
                resolveCalls.incrementAndGet()
                check(allowResolve) { "Fresh owner must not resolve during startup" }
                val saved = listOf(complete, partial, missing).single { it.candidateId == candidate.id }
                AppResult.Success(ResolvedPlayback(candidate.id, saved.representation, TransportKind.PROGRESSIVE,
                    MediaRequest("${origin.uri}/${candidate.id.value}?signature=test-secret", emptyMap()), null, 1))
            }, { route }, { OkHttpClient() })
            store.manager.minRetryCount = 0
        }
        storageOpen = true
        store.startup.await()
    }

    suspend fun download(asset: OfflineAsset, state: DownloadState) {
        store.mutex.withLock {
            repository.saveMapping(asset.copy(state = DownloadState.QUEUED))
            withContext(Dispatchers.Main) { store.manager.addDownload(store.request(asset)); store.manager.resumeDownloads() }
        }
        withTimeout(20_000) { while (!store.mutex.withLock {
            repository.findAsset(asset.id)?.state == state && store.manager.downloadIndex.getDownload(asset.id.value)?.state in
                setOf(Download.STATE_COMPLETED, Download.STATE_FAILED)
        }) delay(20) }
    }

    fun assertStablePersistentValues() {
        store.manager.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) {
            val request = cursor.download.request
            assertEquals(StableCacheIdentity.uri(AssetId(request.id)), request.uri.toString())
            assertSafe(request.uri.toString()); assertSafe(request.customCacheKey.orEmpty())
            assertSafe(request.data.toString(Charsets.UTF_8))
        } }
        for (key in store.cache.keys) {
            assertSafe(key)
            val metadata = store.cache.getContentMetadata(key)
            assertFalse(metadata.contains(ContentMetadata.KEY_REDIRECTED_URI))
            (metadata as DefaultContentMetadata).entrySet().forEach { (name, value) ->
                assertSafe(name); assertSafe(value.toString(Charsets.ISO_8859_1))
            }
        }
    }

    fun assertDiskHasNoRuntimeAddresses() {
        check(!storageOpen)
        val files = File(root, "databases").walkTopDown().filter { it.isFile }.toList()
        assertTrue("Actual Room database exists", files.any { it.name == "music.db" })
        assertTrue("Actual Media3 database exists", files.any { it.name == "exoplayer_internal.db" })
        // Inspect persistent databases (including any journals), never raw cache span media files.
        files.forEach { file ->
            val bytes = file.readBytes()
            assertSafe(bytes.toString(Charsets.ISO_8859_1))
            assertSafe(bytes.toString(Charsets.UTF_16LE))
        }
    }

    fun removeClosedCacheKey(key: String) {
        check(!storageOpen)
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(File(root, "downloads/media"), NoOpCacheEvictor(), database)
        try { cache.removeResource(key); assertTrue(cache.getCachedSpans(key).isEmpty()) }
        finally { cache.release(); database.close() }
    }

    suspend fun closeStorage() {
        if (!storageOpen) return
        val idle = CompletableDeferred<Unit>()
        val listener = object : DownloadManager.Listener {
            override fun onIdle(downloadManager: DownloadManager) { if (downloadManager.isIdle) idle.complete(Unit) }
        }
        try {
            withContext(Dispatchers.Main) {
                store.manager.addListener(listener)
                store.manager.pauseDownloads()
                store.manager.setStopReason("fresh-idle-${UUID.randomUUID()}", 1)
            }
            withTimeout(15_000) { idle.await() }
        } finally { withContext(Dispatchers.Main) { store.manager.removeListener(listener) } }
        // A timeout leaves storage owned: release itself does not join a cache writer.
        store.scope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.Main) { store.manager.release() }
        withContext(Dispatchers.IO) { store.cache.release(); store.database.close(); musicDatabase!!.close() }
        musicDatabase = null
        storageOpen = false
    }

    suspend fun close() { closeStorage(); withContext(Dispatchers.IO) { root.deleteRecursively() } }
    private fun assertSafe(value: String) {
        assertFalse("Runtime signature must not persist", value.contains("signature=test-secret"))
        assertFalse("Runtime origin must not persist", value.contains(origin.uri))
    }
    private fun asset(id: String): OfflineAsset {
        val source = SourceRef("fixture", "audio", id)
        val candidate = PlaybackCandidate(CandidateId(id), source, "original generated PCM", 1000, MatchConfidence.HIGH)
        val track = Track(TrackId(id), "Restart $id", emptyList(), null, Artwork(ArtworkKind.PLACEHOLDER, null, null),
            1000, setOf(source), MetadataProvenance(source, 0, false), listOf(candidate))
        return OfflineAsset(AssetId(id), track.id, track, candidate.id, source,
            Representation("wav", "audio/wav", "pcm", origin.audio.size.toLong(), 1000, null),
            DownloadPolicy().keyFor(track.id, candidate.id, "wav"), 0, DownloadState.QUEUED)
    }
    companion object {
        suspend fun create(origin: RestartOrigin) = RestartFixture(origin).also { it.open(allowResolve = true, seed = true) }
    }
}

private class RestartOrigin : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { Thread(it, "restart-origin").apply { isDaemon = true } }
    private val sockets = CopyOnWriteArrayList<Socket>()
    val requests = CopyOnWriteArrayList<String>()
    val uri = "http://127.0.0.1:${server.localPort}"
    val audio = ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
        repeat(16000) { putShort((sin(it * 2 * Math.PI * 440 / 16000) * 2000).toInt().toShort()) }
    }.array()
    init { pool.submit { while (!server.isClosed) try {
        val socket = server.accept(); sockets += socket
        pool.submit { socket.use { runCatching {
            socket.soTimeout = 8000
            val input = socket.getInputStream(); val head = ByteArrayOutputStream(); var tail = 0
            while (head.size() < 32768) {
                val next = input.read(); if (next < 0) break
                head.write(next); tail = (tail shl 8) or next; if (tail == 0x0d0a0d0a) break
            }
            val request = head.toString("ISO-8859-1"); requests += request
            val partial = request.lineSequence().first().startsWith("GET /partial?")
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nETag: \"restart-fixture\"\r\n" +
                "Content-Length: ${audio.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(audio, 0, if (partial) 8192 else audio.size); output.flush()
        } }; sockets -= socket }
    } catch (_: java.io.IOException) { break } } }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; pool.shutdownNow() }
}
