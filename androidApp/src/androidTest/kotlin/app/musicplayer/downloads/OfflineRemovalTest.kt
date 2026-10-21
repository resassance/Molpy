package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineRemovalTest {
    @Test
    fun removingAssetsPreservesLibraryAndSharedArtworkThenReportsZeroOwnedBytes() = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(OkHttp)
        try {
            val repository = fixture.repository
            repository.upsert(fixture.asset.trackSnapshot)
            repository.setFavorite(fixture.asset.trackId, true)
            repository.recordStarted(fixture.asset.trackId, 1)
            val resources = resources(fixture, client, scope)
            val artwork =
                File(resources.directory.apply { mkdirs() }, "a".repeat(64) + ".artwork").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            val first =
                fixture.asset.copy(
                    trackSnapshot =
                        fixture.asset.trackSnapshot.copy(
                            artwork =
                                fixture.asset.trackSnapshot.artwork.copy(
                                    localKey = artwork.absolutePath
                                )
                        )
                )
            val second =
                first.copy(
                    id = AssetId("second"),
                    representation = first.representation.copy(id = "wav-2"),
                    storageKey = DownloadPolicy().keyFor(first.trackId, first.candidateId, "wav-2"),
                )
            repository.saveMapping(first)
            repository.saveMapping(second)
            fixture.populate(target = first)
            fixture.populate(target = second)
            fixture.cache.removeResource(first.storageKey)
            repository.removeMapping(first.id)
            resources.reconcile()
            assertTrue(artwork.isFile)
            assertNotNull(repository.getTrack(first.trackId))
            assertEquals(first.trackId, repository.favorites.first().single().id)
            assertEquals(first.trackId, repository.recent.first().single().id)
            val remaining = StorageUsage(fixture.cache, repository, resources).measure()
            assertEquals(second.bytes + artwork.length(), remaining.totalBytes)
            assertEquals(remaining.totalBytes, remaining.perAssetBytes[second.id])
            fixture.cache.removeResource(second.storageKey)
            repository.removeMapping(second.id)
            resources.reconcile()
            assertFalse(artwork.exists())
            assertEquals(
                0L,
                StorageUsage(fixture.cache, repository, resources).measure().totalBytes,
            )
            assertNotNull(repository.getTrack(first.trackId))
            assertEquals(1, repository.favorites.first().size)
            assertEquals(1, repository.recent.first().size)
        } finally {
            scope.cancel()
            client.close()
            fixture.close()
        }
    }

    @Test
    fun artworkReferencedByLibrarySnapshotSurvivesOfflineRemoval() = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(OkHttp)
        try {
            val resources = resources(fixture, client, scope)
            val file =
                File(resources.directory.apply { mkdirs() }, "b".repeat(64) + ".artwork").apply {
                    writeBytes(byteArrayOf(4, 5))
                }
            val track =
                fixture.asset.trackSnapshot.copy(
                    artwork = fixture.asset.trackSnapshot.artwork.copy(localKey = file.absolutePath)
                )
            fixture.repository.upsert(track)
            fixture.repository.setFavorite(track.id, true)
            fixture.repository.saveMapping(fixture.asset.copy(trackSnapshot = track))
            fixture.repository.removeMapping(fixture.asset.id)
            resources.reconcile()
            assertTrue(file.exists())
            assertEquals(track, fixture.repository.favorites.first().single())
        } finally {
            scope.cancel()
            client.close()
            fixture.close()
        }
    }

    @Test
    fun concurrentRemovalDuringControlledArtworkFetchCannotResurrectAsset() = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(OkHttp)
        ControlledArtwork().use { server ->
            try {
                val track =
                    fixture.asset.trackSnapshot.copy(
                        artwork = Artwork(ArtworkKind.RELEASE, server.uri, null)
                    )
                val asset = fixture.asset.copy(trackSnapshot = track)
                fixture.repository.saveMapping(asset)
                val gate = Mutex()
                val resources = resources(fixture, client, scope, true, gate)
                val saving =
                    async(Dispatchers.IO) {
                        resources.save(
                            track,
                            track.candidates.single { it.id == asset.candidateId },
                            asset.id,
                        )
                    }
                withTimeout(10_000) { server.requested.await() }
                gate.withLock { fixture.repository.removeMapping(asset.id) }
                server.release.countDown()
                withTimeout(10_000) { saving.await() }
                resources.reconcile()
                assertNull(fixture.repository.findAsset(asset.id))
                assertEquals(
                    0L,
                    StorageUsage(fixture.cache, fixture.repository, resources).measure().totalBytes,
                )
            } finally {
                scope.cancel()
                client.close()
                fixture.close()
            }
        }
    }

    @Test
    fun successfulArtworkEnrichmentPreservesRecordedSnapshotAndCandidateOrder() = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(OkHttp)
        ControlledArtwork(initiallyReleased = true).use { server ->
            try {
                val track =
                    fixture.asset.trackSnapshot.copy(
                        artwork = Artwork(ArtworkKind.RELEASE, server.uri, null)
                    )
                val asset = fixture.asset.copy(trackSnapshot = track)
                fixture.populate()
                fixture.repository.saveMapping(asset)
                val resources = resources(fixture, client, scope, true)
                resources.save(
                    track,
                    track.candidates.single { it.id == asset.candidateId },
                    asset.id,
                )
                val saved = requireNotNull(fixture.repository.findAsset(asset.id))
                val key = requireNotNull(saved.trackSnapshot.artwork.localKey)
                assertTrue(resources.ownedFile(key)?.isFile == true)
                assertEquals(
                    asset.copy(
                        trackSnapshot = track.copy(artwork = track.artwork.copy(localKey = key))
                    ),
                    saved,
                )
                assertEquals(track.candidates, saved.trackSnapshot.candidates)
                assertEquals(DownloadState.AVAILABLE, saved.state)
            } finally {
                scope.cancel()
                client.close()
                fixture.close()
            }
        }
    }

    @Test
    fun ancillaryFailuresDoNotDemoteVerifiedAudioAndLyricsUseRecordedCandidate() = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(OkHttp)
        ControlledArtwork(status = 500, initiallyReleased = true).use { server ->
            try {
                val track =
                    fixture.asset.trackSnapshot.copy(
                        artwork = Artwork(ArtworkKind.RELEASE, server.uri, null)
                    )
                val asset = fixture.asset.copy(trackSnapshot = track)
                fixture.populate()
                fixture.repository.saveMapping(asset)
                var fetchedCandidate: CandidateId? = null
                val lyrics =
                    LyricsLoader(
                        listOf(
                            object : LyricsPort {
                                override suspend fun fetch(
                                    track: Track,
                                    candidate: PlaybackCandidate,
                                ): AppResult<LyricsCandidate?> {
                                    fetchedCandidate = candidate.id
                                    return AppResult.Failure(
                                        AppError(ErrorCode.NETWORK, "lyrics.fixture", true)
                                    )
                                }
                            }
                        ),
                        LyricsSelector(),
                        fixture.repository,
                    ) {
                        0L
                    }
                val resources =
                    OfflineResources(
                        File(fixture.root, "resources"),
                        fixture.repository,
                        fixture.repository,
                        ArtworkLoader(fixture.context, client),
                        lyrics,
                        scope,
                        { true },
                    )
                resources.save(
                    track,
                    track.candidates.single { it.id == asset.candidateId },
                    asset.id,
                )
                assertEquals(DownloadState.AVAILABLE, fixture.repository.findAsset(asset.id)?.state)
                assertNull(fixture.repository.findAsset(asset.id)?.trackSnapshot?.artwork?.localKey)
                assertEquals(asset.candidateId, fetchedCandidate)
                assertNull(fixture.repository.getLyrics(track.id, asset.candidateId, 0))
            } finally {
                scope.cancel()
                client.close()
                fixture.close()
            }
        }
    }

    @Test
    fun reconcileKeepsArtworkOwnedOutsideLegacyFavoritesAndQueue() = runBlocking {
        val fixture = OfflineNativeFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(OkHttp)
        try {
            val directory = File(fixture.root, "resources").apply { mkdirs() }
            val file =
                File(directory, "a".repeat(64) + ".artwork").apply { writeBytes(byteArrayOf(1)) }
            var retained = setOf(file.absolutePath)
            val resources =
                OfflineResources(
                    directory,
                    fixture.repository,
                    fixture.repository,
                    ArtworkLoader(fixture.context, client),
                    LyricsLoader(emptyList(), LyricsSelector(), fixture.repository) { 0L },
                    scope,
                    { false },
                    retainedArtworkKeys = { retained },
                )
            resources.reconcile()
            assertTrue(file.isFile)
            retained = emptySet()
            resources.reconcile()
            assertFalse(file.exists())
        } finally {
            scope.cancel()
            client.close()
            fixture.close()
        }
    }

    private fun resources(
        fixture: OfflineNativeFixture,
        client: HttpClient,
        scope: CoroutineScope,
        online: Boolean = false,
        gate: Mutex = Mutex(),
    ) =
        OfflineResources(
            File(fixture.root, "resources"),
            fixture.repository,
            fixture.repository,
            ArtworkLoader(fixture.context, client),
            LyricsLoader(emptyList(), LyricsSelector(), fixture.repository) { 0L },
            scope,
            { online },
            { action -> gate.withLock { action() } },
        )
}

/** Deterministic original image bytes over a controlled loopback response; no external provider. */
private class ControlledArtwork(private val status: Int = 200, initiallyReleased: Boolean = false) :
    AutoCloseable {
    val requested = CompletableDeferred<Unit>()
    val release = CountDownLatch(if (initiallyReleased) 0 else 1)
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val uri = "http://127.0.0.1:${server.localPort}/generated-image"
    private val worker =
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    requested.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    val bitmap =
                        android.graphics.Bitmap.createBitmap(
                            2,
                            2,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    val bytes =
                        try {
                            bitmap.eraseColor(0xff446688.toInt())
                            java.io.ByteArrayOutputStream().use { output ->
                                check(
                                    bitmap.compress(
                                        android.graphics.Bitmap.CompressFormat.PNG,
                                        100,
                                        output,
                                    )
                                )
                                output.toByteArray()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    socket
                        .getOutputStream()
                        .write(
                            ("HTTP/1.1 $status Fixture\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray()
                        )
                    socket.getOutputStream().write(bytes)
                }
            }
        }

    override fun close() {
        release.countDown()
        server.close()
        worker.join(1000)
    }
}
