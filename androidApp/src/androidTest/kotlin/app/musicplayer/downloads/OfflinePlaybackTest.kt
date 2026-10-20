package app.musicplayer.downloads

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import app.musicplayer.playback.*
import app.musicplayer.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Compile-retained native fixtures: actual cache, extractor/player and original generated PCM. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflinePlaybackTest {
    @Test fun restartedCachePlaysAndSeeksSavedNonFirstRecordingWithoutHttp() = runBlocking {
        val fixture = OfflineNativeFixture()
        var engine: AndroidPlayerEngine? = null
        try {
            fixture.populate()
            fixture.repository.saveMapping(fixture.asset)
            assertTrue(DownloadVerifier(fixture.cache) { true }.verify(fixture.asset) is AppResult.Success)
            fixture.restartStorage()
            val local = OfflineMediaSource(fixture.cache)
            var resolverCalls = 0
            val resolver = object : PlaybackResolver {
                override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> {
                    resolverCalls++; error("Restarted offline recording must not resolve")
                }
            }
            val selector = PlaybackSourceSelector(resolver,
                { NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), false, false) }, fixture.repository, local::select)
            val selected = selector.select(fixture.asset.trackSnapshot.copy(title = "Updated catalog title")).valueOrNull() as PlayableSource.Local
            assertEquals(0, resolverCalls)
            assertEquals(fixture.asset.trackSnapshot, selected.track)
            assertEquals(fixture.asset.candidateId, selected.candidateId)
            // A nonzero-position read uses Media3's key lookup, not fragment paths.
            val source = local.dataSourceFactory.createDataSource()
            val tail = ByteArray(64)
            try {
                source.open(DataSpec.Builder().setUri(selected.stableUri).setKey(selected.cacheKey)
                    .setPosition(fixture.audio.size - 64L).setLength(64).build())
                var offset = 0
                while (offset < tail.size) {
                    val count = source.read(tail, offset, tail.size - offset)
                    check(count > 0); offset += count
                }
                assertArrayEquals(fixture.audio.takeLast(64).toByteArray(), tail)
            } finally { source.close() }
            var httpRequests = 0
            withContext(Dispatchers.Main) {
                engine = AndroidPlayerEngine(fixture.context, { httpRequests++; error("No local HTTP") }, local.dataSourceFactory)
                assertTrue(engine!!.load(QueueEntry(QueueEntryId("offline"), selected.track.id), selected.copy(loadId = 5), 0, false) is AppResult.Success)
            }
            withTimeout(10_000) { while (engine!!.snapshot.value.status != PlayerStatus.PAUSED) {
                assertNotEquals(PlayerStatus.FAILED, engine!!.snapshot.value.status); delay(20)
            } }
            withContext(Dispatchers.Main) { engine!!.seek(1500); engine!!.play() }
            withTimeout(10_000) { while (engine!!.snapshot.value.status != PlayerStatus.ENDED) {
                assertNotEquals(PlayerStatus.FAILED, engine!!.snapshot.value.status); delay(20)
            } }
            assertEquals(0, httpRequests)
            assertEquals(5L, engine!!.snapshot.value.loadId)
            assertEquals(fixture.asset.trackSnapshot, engine!!.snapshot.value.track)
            assertEquals(fixture.asset.candidateId, engine!!.snapshot.value.candidateId)
        } finally {
            withContext(Dispatchers.Main) { engine?.release() }
            fixture.close()
        }
    }

    @Test fun cacheDisappearingAfterSelectionFailsLocallyAndRevokesAvailability() = brokenAfterSelection(false)
    @Test fun corruptContentFailsLocallyAndRevokesAvailability() = brokenAfterSelection(true)

    private fun brokenAfterSelection(corrupt: Boolean) = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var engine: AndroidPlayerEngine? = null
        var coordinator: PlaybackCoordinator? = null
        try {
            fixture.populate(); fixture.repository.saveMapping(fixture.asset)
            val local = OfflineMediaSource(fixture.cache)
            var remoteCalls = 0
            val resolver = object : PlaybackResolver {
                override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> {
                    remoteCalls++; error("Broken local media must not resolve")
                }
            }
            val route = NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), false, false)
            val selector = PlaybackSourceSelector(resolver, { route }, fixture.repository, { asset ->
                val ready = local.select(asset)
                fixture.cache.removeResource(asset.storageKey)
                if (corrupt) fixture.populate(ByteArray(fixture.audio.size))
                ready
            })
            withContext(Dispatchers.Main) {
                engine = AndroidPlayerEngine(fixture.context, { remoteCalls++; error("No local HTTP") }, local.dataSourceFactory)
                coordinator = PlaybackCoordinator(engine!!, resolver, { route }, scope, sourceSelector = selector)
                coordinator!!.select(fixture.asset.trackSnapshot, QueueEntry(QueueEntryId("broken"), fixture.asset.trackId), 0, true)
            }
            withTimeout(10_000) { while (fixture.repository.findAsset(fixture.asset.id)?.state != DownloadState.FAILED) delay(20) }
            assertEquals(ErrorCode.INCOMPLETE_MEDIA, coordinator!!.snapshot.value.error?.code)
            assertEquals(0, remoteCalls)
        } finally {
            withContext(Dispatchers.Main) { coordinator?.release() ?: engine?.release() }
            scope.cancel(); fixture.close()
        }
    }
}

@UnstableApi
internal class OfflineNativeFixture {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(base.noBackupFilesDir, "offline-native-${UUID.randomUUID()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private var musicDatabase = createMusicDatabase(context)
    var repository = RoomLibraryRepository(musicDatabase) { 0L }
        private set
    private val cacheDatabase = StandaloneDatabaseProvider(context)
    var cache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), cacheDatabase)
    private val source = SourceRef("fixture", "audio", "saved")
    private val candidate = PlaybackCandidate(CandidateId("saved"), source, "recorded", 2000, MatchConfidence.HIGH)
    private val other = candidate.copy(id = CandidateId("other"), source = SourceRef("fixture", "audio", "other"), version = "different recording")
    private val track = Track(TrackId("saved"), "Saved recording", emptyList(), null,
        Artwork(ArtworkKind.PLACEHOLDER, null, null), 2000, setOf(source), MetadataProvenance(source, 0, false), listOf(other, candidate))
    val audio = ByteBuffer.allocate(44 + 16000 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
        repeat(32000) { putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 16000) * 2000).toInt().toShort()) }
    }.array()
    val asset = OfflineAsset(AssetId("saved"), track.id, track, candidate.id, source,
        Representation("wav", "audio/wav", "pcm", audio.size.toLong(), 2000, null),
        DownloadPolicy().keyFor(track.id, candidate.id, "wav"), audio.size.toLong(), DownloadState.AVAILABLE)
    fun populate(bytes: ByteArray = audio, target: OfflineAsset = asset) {
        val hole = requireNotNull(cache.startReadWrite(target.storageKey, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(target.storageKey, 0, bytes.size.toLong())
            file.writeBytes(bytes); cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(target.storageKey, ContentMetadataMutations().set(ContentMetadata.KEY_CONTENT_LENGTH, bytes.size.toLong()))
        ResourceStampMetadata.write(cache, target.storageKey, ResourceStamp(target.source, target.representation.id, null, bytes.size.toLong()))
    }
    fun restartStorage() {
        cache.release(); musicDatabase.close()
        musicDatabase = createMusicDatabase(context)
        repository = RoomLibraryRepository(musicDatabase) { 0L }
        cache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), cacheDatabase)
    }
    fun close() { cache.release(); cacheDatabase.close(); musicDatabase.close(); root.deleteRecursively() }
}
