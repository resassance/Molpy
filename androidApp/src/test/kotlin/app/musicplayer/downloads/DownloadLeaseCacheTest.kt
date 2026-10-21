package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadLeaseCacheTest {
    private val source = SourceRef("youtube", "video", "one")
    private val candidate =
        PlaybackCandidate(CandidateId("one"), source, "", 188000, MatchConfidence.HIGH)
    private val track =
        Track(
            TrackId("one"),
            "Song",
            emptyList(),
            null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null),
            188000,
            setOf(source),
            MetadataProvenance(source, 0, false),
            listOf(candidate),
        )
    private val representation = Representation("140", "audio/mp4", "aac", 10000, null, null)
    private val asset =
        OfflineAsset(
            AssetId("one"),
            track.id,
            track,
            candidate.id,
            source,
            representation,
            "offline-one",
            0,
            DownloadState.QUEUED,
        )

    private fun lease(expires: Long? = 200000) =
        ResolvedPlayback(
            candidate.id,
            representation,
            TransportKind.PROGRESSIVE,
            MediaRequest("https://media.test/temporary", emptyMap()),
            expires,
            1,
        )

    @Test
    fun onlyCurrentAttemptAndRouteMayReuseLease() {
        val cache = DownloadLeaseCache { 100000 }
        val lease = lease()
        cache.activate(asset.id, "current")
        cache.remember(asset, "current", lease)
        assertSame(lease, cache.takeValid(asset, "current", 1, 100000))
        assertNull(cache.takeValid(asset, "old", 1, 100000))
        assertSame(lease, cache.takeValid(asset, "current", 1, 100000))
        assertNull(cache.takeValid(asset, "current", 2, 100000))
    }

    @Test
    fun expiryAndUnknownExpiryPreventReuse() {
        for (expires in listOf(null, 130000L, 120000L)) {
            val cache = DownloadLeaseCache { 100000 }
            cache.activate(asset.id, "current")
            cache.remember(asset, "current", lease(expires))
            assertNull(cache.takeValid(asset, "current", 1, 100000))
        }
        val cache = DownloadLeaseCache { 100000 }
        cache.activate(asset.id, "current")
        cache.remember(asset, "current", lease(999999))
        assertNull(cache.takeValid(asset, "current", 1, 220000))
    }

    @Test
    fun invalidatedAttemptCannotRefillTheCache() {
        val cache = DownloadLeaseCache { 100000 }
        cache.activate(asset.id, "old")
        cache.invalidate(asset.id)
        cache.remember(asset, "old", lease())
        assertNull(cache.takeValid(asset, "old", 1, 100000))
        cache.activate(asset.id, "new")
        cache.remember(asset, "old", lease())
        assertNull(cache.takeValid(asset, "new", 1, 100000))
    }

    @Test
    fun firstOpenReusesAdmissionLeaseWithoutAnotherExtraction() = runBlocking {
        val cache = DownloadLeaseCache { 100000 }
        cache.activate(asset.id, "current")
        cache.remember(asset, "current", lease())
        val result =
            cache.resolve(asset, "current", 1) {
                error("Initial open must reuse the admission extraction")
            }
        assertNotNull(result.valueOrNull())
    }

    @Test
    fun lateExtractionCannotRefillANewerAttempt() = runBlocking {
        val cache = DownloadLeaseCache { 100000 }
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        cache.activate(asset.id, "old")
        val resolving = async {
            cache.resolve(asset, "old", 1) {
                started.complete(Unit)
                gate.await()
                AppResult.Success(lease())
            }
        }
        started.await()
        cache.invalidate(asset.id)
        cache.activate(asset.id, "new")
        gate.complete(Unit)
        assertEquals(ErrorCode.RESOURCE_CHANGED, resolving.await().errorOrNull()?.code)
        assertNull(cache.takeValid(asset, "new", 1, 100000))
    }

    @Test
    fun onlyEightOwnerLeasesAreRetained() {
        val cache = DownloadLeaseCache { 100000 }
        repeat(9) {
            val next = asset.copy(id = AssetId("asset-$it"))
            cache.activate(next.id, "token-$it")
            cache.remember(next, "token-$it", lease())
        }
        assertNull(cache.takeValid(asset.copy(id = AssetId("asset-0")), "token-0", 1, 100000))
        assertNotNull(cache.takeValid(asset.copy(id = AssetId("asset-8")), "token-8", 1, 100000))
    }

    @Test
    fun changedSourceCannotReuseAnOldLeaseUnderSameCandidateId() {
        val cache = DownloadLeaseCache { 100000 }
        cache.activate(asset.id, "current")
        cache.remember(asset, "current", lease())
        val otherSource = source.copy(id = "two")
        val otherTrack =
            track.copy(
                candidates = listOf(candidate.copy(source = otherSource)),
                externalIds = setOf(otherSource),
            )
        val changed = asset.copy(source = otherSource, trackSnapshot = otherTrack)
        assertNull(cache.takeValid(changed, "current", 1, 100000))
    }
}
