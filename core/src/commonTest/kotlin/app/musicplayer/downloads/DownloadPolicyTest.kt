package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlin.test.*

class DownloadPolicyTest {
    private val policy = DownloadPolicy()
    @Test fun admitsOnlyOneExplicitTransfer() {
        var active = 0
        repeat(5) { if (policy.mayStart(active)) active++ }
        assertEquals(1, active)
        assertTrue(policy.mayStart(0))
        assertFalse(policy.mayStart(1))
        assertFalse(policy.mayStart(-1))
    }
    @Test fun sameRepresentationDeduplicatesButOtherIdentityNeverAliases() {
        val first = policy.keyFor(TrackId("a:b"), CandidateId("c"), "d")
        assertEquals(first, policy.keyFor(TrackId("a:b"), CandidateId("c"), "d"))
        assertNotEquals(first, policy.keyFor(TrackId("a"), CandidateId("b:c"), "d"))
        assertNotEquals(first, policy.keyFor(TrackId("a:b"), CandidateId("c"), "other"))
        assertNotEquals(first, policy.keyFor(TrackId("a:b"), CandidateId("other"), "d"))
        assertFailsWith<IllegalArgumentException> { policy.keyFor(TrackId("a"), CandidateId("c"), " ") }
    }
    @Test fun onlyCompleteAndVerifiedTransportMayBecomeAvailable() {
        assertFalse(policy.mayPublishAvailable(false, false))
        assertFalse(policy.mayPublishAvailable(true, false))
        assertFalse(policy.mayPublishAvailable(false, true))
        assertTrue(policy.mayPublishAvailable(true, true))
    }
    @Test fun assetRequiresMatchingSnapshotAndCandidateSource() {
        val source = SourceRef("test", "song", "one")
        val candidate = PlaybackCandidate(CandidateId("candidate"), source, "studio", 100, MatchConfidence.HIGH)
        val track = Track(TrackId("one"), "Song", emptyList(), null, Artwork(ArtworkKind.PLACEHOLDER, null, null),
            100, setOf(source), MetadataProvenance(source, 0, false), listOf(candidate))
        val partial = OfflineAsset(AssetId("asset"), track.id, track, candidate.id, source,
            Representation("format", "audio/test", "test", 10, 100, null), "local-key", 2, DownloadState.DOWNLOADING)
        assertNotEquals(DownloadState.AVAILABLE, partial.state)
        assertFailsWith<IllegalArgumentException> { partial.copy(trackId = TrackId("other")) }
        assertFailsWith<IllegalArgumentException> { partial.copy(candidateId = CandidateId("other")) }
        assertFailsWith<IllegalArgumentException> { partial.copy(source = SourceRef("test", "song", "other")) }
        assertFailsWith<IllegalArgumentException> {
            partial.copy(trackSnapshot = track.copy(candidates = listOf(candidate,
                candidate.copy(source = SourceRef("test", "song", "other")))))
        }
        assertFailsWith<IllegalArgumentException> { partial.copy(bytes = -1) }
        assertFailsWith<IllegalArgumentException> { partial.copy(storageKey = "https://cdn.example/media?token=secret") }
    }
}
