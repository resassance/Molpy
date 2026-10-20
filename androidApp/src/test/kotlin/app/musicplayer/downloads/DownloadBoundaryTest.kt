package app.musicplayer.downloads

import app.musicplayer.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadBoundaryTest {
    private val candidate = PlaybackCandidate(CandidateId("recording"), SourceRef("youtube", "video", "one"), "original", 1000, MatchConfidence.HIGH)
    private fun lease(transport: TransportKind = TransportKind.PROGRESSIVE, generation: Long = 1, candidateId: CandidateId = candidate.id) =
        ResolvedPlayback(candidateId, Representation("140", "audio/mp4", "aac", 100, 1000, null), transport,
            MediaRequest("https://fixture.invalid/audio?signature=runtime-only", emptyMap()), null, generation)
    private fun admit(lease: ResolvedPlayback) = DownloadCommandPolicy.admit(lease, candidate, 1L)
    @Test fun enqueueRejectsAdaptiveTransportWithoutSavingAProgressivePrefix() {
        assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, admit(lease(TransportKind.HLS))?.code)
        assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, admit(lease(TransportKind.BOUNDED_RANGE))?.code)
        assertFalse(isProgressiveAudio("audio/x-mpegurl"))
        assertFalse(isProgressiveAudio("application/vnd.apple.mpegurl"))
    }
    @Test fun enqueueRejectsStaleGenerationAndDifferentRecording() {
        assertEquals(ErrorCode.INCOMPATIBLE_RESOURCE, admit(lease(generation = 2))?.code)
        assertEquals(ErrorCode.INCOMPATIBLE_RESOURCE, admit(lease(candidateId = CandidateId("other")))?.code)
        assertNull(admit(lease()))
    }
    @Test fun networkCannotResumeBeforeServiceStartOrAfterTimeoutWithoutExplicitUserStart() {
        val lifetime = DownloadServiceLifetime()
        assertFalse(lifetime.canResume)
        assertFalse("Attachment without explicit intent cannot resume", lifetime.serviceStarted())
        lifetime.serviceStopped()
        lifetime.userStarted()
        assertFalse(lifetime.canResume)
        assertTrue("Explicit start may resume only after attachment", lifetime.serviceStarted())
        assertTrue(lifetime.canResume)
        lifetime.timedOut()
        assertFalse(lifetime.canResume)
        lifetime.serviceStopped()
        assertFalse("Reattachment cannot resume after timeout", lifetime.serviceStarted())
        assertFalse("Service recreation must not clear a dataSync timeout", lifetime.canResume)
        lifetime.userStarted()
        assertTrue(lifetime.canResume)
    }
    @Test fun routeActivationAfterServiceDetachWaitsForAttachmentBeforeResumingIo() {
        val lifetime = DownloadServiceLifetime()
        lifetime.userStarted(); lifetime.serviceStarted()
        assertTrue(lifetime.resumeAfterAttachment { fail("Attached service needs no restart") })
        lifetime.serviceStopped()
        assertFalse("Destroyed service cannot own IO", lifetime.canResume)
        var starts = 0
        assertFalse(lifetime.resumeAfterAttachment { starts++ })
        assertEquals(1, starts)
        assertFalse("Accepted start is not service attachment", lifetime.canResume)
        assertTrue("Ordinary detach preserves explicit start admission", lifetime.serviceStarted())
        assertTrue("Ordinary detach preserves explicit continuation", lifetime.resumeAfterAttachment { fail("Already attached") })
    }
    @Test fun rejectedForegroundRestartKeepsIoPausedAndContinuationAvailableForLaterAttachment() {
        val lifetime = DownloadServiceLifetime()
        lifetime.userStarted(); lifetime.serviceStarted(); lifetime.serviceStopped()
        var starts = 0
        assertFalse(lifetime.resumeAfterAttachment { starts++; throw IllegalStateException("Background start rejected") })
        assertEquals(1, starts)
        assertFalse(lifetime.canResume)
        assertFalse(lifetime.resumeAfterAttachment { starts++ })
        assertEquals("Rejected start must not discard continuation", 2, starts)
        lifetime.serviceStarted()
        assertTrue(lifetime.resumeAfterAttachment { fail("Already attached") })
    }
    @Test fun recoveryClearsOnlyTheRelevantAssetFailure() {
        val failures = DownloadFailureState()
        val error = AppError(ErrorCode.STORAGE_FULL, "download.fixture", true)
        failures.record(AssetId("one"), error); failures.record(AssetId("two"), error)
        failures.clear(AssetId("one"))
        assertEquals(mapOf(AssetId("two") to error), failures.values.value)
    }
    @Test fun nestedTypedFailuresKeepTheirCodeWithoutRetainingRuntimeExceptionDetails() {
        val error = AppError(ErrorCode.PROXY_AUTH, "network.proxy", false)
        val normalized = downloadError(IOException("signature=runtime-only", AppFailureException(error)), "download.transfer")
        assertEquals(error, normalized)
        val failure = DownloadFailure(normalized)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("runtime-only"))
    }
}
