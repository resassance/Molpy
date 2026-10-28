package app.musicplayer.downloads

import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import app.musicplayer.model.*
import org.junit.Assert.*
import org.junit.Test

class DownloadRecoveryTest {
    @Test
    fun probeSizeMismatchRejectsResponseBeforeCacheWrites() {
        val result =
            DownloadAdmission.evaluate(
                SourceRef("youtube", "video", "one"),
                Representation("audio", "audio/webm", "opus", 1000, null, null),
                null,
                0,
                206,
                mapOf(
                    "Content-Range" to listOf("bytes 0-999/2000"),
                    "Content-Length" to listOf("1000"),
                ),
                false,
            )
        assertNotNull(result.error)
        assertNull(result.stamp)
    }

    private val source = SourceRef("fixture", "audio", "one")
    private val representation = Representation("wav", "audio/wav", "pcm", 100, 1000, null)
    private val saved = ResourceStamp(source, "wav", "\"one\"", 100)

    private fun admit(
        offset: Long = 40,
        status: Int = 206,
        headers: Map<String, List<String>> =
            mapOf(
                "ETag" to listOf("\"one\""),
                "Content-Range" to listOf("bytes 40-99/100"),
                "Content-Length" to listOf("60"),
            ),
        previous: ResourceStamp? = saved,
        bytes: Boolean = true,
        current: Representation = representation,
    ) = DownloadAdmission.evaluate(source, current, previous, offset, status, headers, bytes)

    @Test
    fun automaticRestartBudgetSurvivesRestorationAndExplicitRetryResetsIt() {
        val first = DownloadAttempt("asset\nformat\nuuid")
        assertTrue(first.claimAutomaticRestart())
        assertFalse(first.claimAutomaticRestart())
        val restored = DownloadAttempt("asset\nformat\nuuid\nrestart=1")
        assertFalse(restored.claimAutomaticRestart())
        val explicit = DownloadAttempt("asset\nformat\nnew-uuid")
        assertTrue(explicit.claimAutomaticRestart())
    }

    @Test
    fun expiredResponseGetsOneFreshResolutionInsteadOfCodecError() {
        val result = admit(0, 403, emptyMap(), null, false)
        assertEquals(ErrorCode.URL_REJECTED, result.error?.code)
        assertTrue(result.restart)
    }

    @Test
    fun explicitNonAudioResponseIsRejectedBeforeReading() {
        for (mime in
            listOf("text/html; charset=utf-8", "application/json", "application/problem+json")) {
            val result =
                admit(
                    0,
                    200,
                    mapOf("Content-Type" to listOf(mime), "Content-Length" to listOf("100")),
                    null,
                    false,
                )
            assertEquals(ErrorCode.INCOMPLETE_MEDIA, result.error?.code)
            assertFalse(result.restart)
        }
        for (headers in
            listOf(
                emptyMap(),
                mapOf("Content-Type" to listOf("application/octet-stream")),
                mapOf("Content-Type" to listOf("audio/wav")),
            )) {
            assertNull(admit(0, 200, headers, null, false).error)
        }
    }

    @Test
    fun unchangedCorrectRangeAdmitsTheSavedIdentity() {
        assertEquals(saved, admit().stamp)
        assertNull(admit().error)
    }

    @Test
    fun ignoredRangeAndChangedValidatorRequireOwnerResetBeforeBytes() {
        assertTrue(admit(status = 200).restart)
        assertTrue(
            admit(
                    headers =
                        mapOf(
                            "ETag" to listOf("\"two\""),
                            "Content-Range" to listOf("bytes 40-99/100"),
                        )
                )
                .restart
        )
    }

    @Test
    fun weakMissingAndConflictingValidatorsCannotResume() {
        for (etag in listOf(emptyList(), listOf("W/\"one\""), listOf("\"one\"", "\"two\""))) {
            assertNotNull(
                admit(headers = mapOf("ETag" to etag, "Content-Range" to listOf("bytes 40-99/100")))
                    .error
            )
        }
    }

    @Test
    fun malformedRangeLengthAndBodyEncodingCannotAdmitBytes() {
        for (range in
            listOf("bytes 41-99/100", "bytes 40-100/100", "bytes 40-99/*", "bytes 40-99/101")) {
            assertNotNull(
                admit(
                        headers =
                            mapOf("ETag" to listOf("\"one\""), "Content-Range" to listOf(range))
                    )
                    .error
            )
        }
        assertNotNull(
            admit(
                    headers =
                        mapOf(
                            "ETag" to listOf("\"one\""),
                            "Content-Range" to listOf("bytes 40-99/100"),
                            "Content-Length" to listOf("59"),
                        )
                )
                .error
        )
        assertNotNull(
            admit(
                    headers =
                        mapOf(
                            "ETag" to listOf("\"one\""),
                            "Content-Range" to listOf("bytes 40-99/100"),
                            "Content-Encoding" to listOf("gzip"),
                        )
                )
                .error
        )
    }

    @Test
    fun initialResponseProducesActualStampRatherThanLocalExtent() {
        val result =
            admit(
                0,
                200,
                mapOf("ETag" to listOf("\"one\""), "Content-Length" to listOf("100")),
                null,
                false,
            )
        assertEquals(saved, result.stamp)
        val unknown =
            admit(0, 200, emptyMap(), null, false, representation.copy(expectedBytes = null))
        assertEquals(ResourceStamp(source, "wav", null, null), unknown.stamp)
        assertNotNull(admit(0, 200, mapOf("Content-Length" to listOf("99")), null, false).error)
    }

    @Test
    fun freshOffsetZeroWithExistingLaterSpansRequiresCleanup() {
        assertTrue(admit(0, 200, mapOf("Content-Length" to listOf("100"))).restart)
        assertTrue(admit(previous = null).restart)
    }

    @Test
    fun changedImmutableRepresentationCannotContinuePartialBytes() {
        assertTrue(admit(current = representation.copy(id = "other")).restart)
        assertTrue(admit(previous = saved.copy(source = source.copy(id = "other"))).restart)
    }

    @Test
    fun rejectionIsStickyAndOnlyFirstOwnerNotificationIsIssued() {
        val attempt = DownloadAttempt("one")
        val error = AppError(ErrorCode.INCOMPATIBLE_RESOURCE, "download.admission", false)
        assertTrue(attempt.reject(error))
        assertFalse(attempt.reject(error.copy(code = ErrorCode.NETWORK)))
        assertEquals(error, attempt.rejection)
    }

    @Test
    fun onlyCurrentUnrejectedAttemptMayPublishCompletion() {
        val attempt = DownloadAttempt("one")
        assertTrue(attempt.matches("one"))
        assertFalse(attempt.matches("old"))
        attempt.reject(AppError(ErrorCode.INCOMPATIBLE_RESOURCE, "download.admission", false))
        assertFalse(attempt.matches("one"))
    }

    @Test
    fun explicitQueuedResumeAfterTimeoutRestoresAdmissionWithoutAnotherAdd() {
        val lifetime = DownloadServiceLifetime()
        lifetime.userStarted()
        lifetime.serviceStarted()
        lifetime.timedOut()
        lifetime.serviceStopped()
        var adds = 0
        var resumes = 0
        dispatchExplicitDownloadStart(
            false,
            Download.STATE_QUEUED,
            0,
            dispatch = { if (it == DownloadServiceCommand.ADD) adds++ else resumes++ },
            userStarted = lifetime::userStarted,
        )
        assertEquals(0, adds)
        assertEquals("Explicit resume must dispatch foreground service admission", 1, resumes)
        assertTrue("Explicit user admission clears the OS timeout latch", lifetime.canContinue)
        assertFalse("Accepted service start cannot authorize detached IO", lifetime.canResume)
        assertTrue(lifetime.serviceStarted())
    }

    @Test
    fun explicitExistingOrPendingTransferAfterDetachRequestsServiceWithoutDuplicateAdd() {
        for ((pending, state) in
            listOf(
                false to Download.STATE_QUEUED,
                false to Download.STATE_DOWNLOADING,
                true to null,
            )) {
            val lifetime = DownloadServiceLifetime()
            lifetime.userStarted()
            lifetime.serviceStarted()
            lifetime.serviceStopped()
            var adds = 0
            var resumes = 0
            dispatchExplicitDownloadStart(
                pending,
                state,
                if (state == null) null else 0,
                dispatch = { if (it == DownloadServiceCommand.ADD) adds++ else resumes++ },
                userStarted = lifetime::userStarted,
            )
            assertEquals(0, adds)
            assertEquals("An indexed or pending transfer still needs service admission", 1, resumes)
            assertFalse(lifetime.canResume)
            assertTrue(lifetime.serviceStarted())
        }
    }

    @Test
    fun rejectedExplicitServiceResumeCannotClearTimeoutOrAuthorizeIo() {
        val lifetime = DownloadServiceLifetime()
        lifetime.userStarted()
        lifetime.serviceStarted()
        lifetime.timedOut()
        lifetime.serviceStopped()
        val failure = runCatching {
            dispatchExplicitDownloadStart(
                false,
                Download.STATE_QUEUED,
                0,
                dispatch = {
                    assertNotEquals(
                        "Queued transfer must not be added again",
                        DownloadServiceCommand.ADD,
                        it,
                    )
                    throw IllegalStateException("Foreground start rejected")
                },
                userStarted = lifetime::userStarted,
            )
        }
            .exceptionOrNull()
        assertTrue(
            "Rejected service dispatch must reach command error handling",
            failure is IllegalStateException,
        )
        assertFalse(lifetime.canContinue)
        assertFalse(lifetime.canResume)
        assertFalse(
            "Service recreation alone cannot clear the timeout latch",
            lifetime.serviceStarted(),
        )
    }

    @Test
    fun explicitNewOrIndividuallyStoppedTransferStillAddsBeforeUserAdmission() {
        for ((state, stopReason) in
            listOf(null to null, Download.STATE_FAILED to 0, Download.STATE_QUEUED to 1)) {
            val lifetime = DownloadServiceLifetime()
            val order = mutableListOf<String>()
            dispatchExplicitDownloadStart(
                false,
                state,
                stopReason,
                dispatch = {
                    assertEquals(
                        "Missing or individually stopped work needs an ADD",
                        DownloadServiceCommand.ADD,
                        it,
                    )
                    order += "add"
                    assertFalse(lifetime.canContinue)
                },
                userStarted = {
                    order += "user"
                    lifetime.userStarted()
                },
            )
            assertEquals(listOf("add", "user"), order)
            assertTrue(lifetime.canContinue)
            assertFalse(lifetime.canResume)
        }
    }

    @Test
    fun delayedExplicitServiceIntentCannotOverrideANewerTimeoutAttachedOrDetached() {
        for (attached in listOf(false, true)) {
            val fixture = AdmissionFixture(attached)
            fixture.explicitStart()
            fixture.lifetime.timedOut()
            fixture.deliverServiceIntent()
            assertEquals(
                "Stale service delivery must not override the newer timeout",
                0,
                fixture.nativeResumes,
            )
            assertTrue(fixture.pending)
            assertFalse(fixture.lifetime.canContinue)
        }
    }

    @Test
    fun delayedExplicitServiceIntentCannotOverrideRouteInvalidationAttachedOrDetached() {
        for (attached in listOf(false, true)) {
            val fixture = AdmissionFixture(attached)
            fixture.explicitStart()
            fixture.ready = false
            fixture.deliverServiceIntent()
            assertEquals(
                "INIT delivery cannot bypass the current route gate",
                0,
                fixture.nativeResumes,
            )
            assertTrue(fixture.pending)
            fixture.ready = true
            fixture.activate()
            assertEquals(1, fixture.nativeResumes)
            assertEquals(1, fixture.retries)
            assertFalse(fixture.pending)
        }
    }

    @Test
    fun delayedReconnectServiceIntentCannotOverrideANewerTimeout() {
        val fixture = AdmissionFixture(attached = false)
        fixture.activate()
        assertTrue(fixture.pending)
        fixture.lifetime.timedOut()
        fixture.deliverServiceIntent()
        assertEquals(
            "Reconnect dispatch cannot overrule a later OS timeout",
            0,
            fixture.nativeResumes,
        )
        assertTrue(fixture.pending)
        assertFalse(fixture.lifetime.canContinue)
    }

    @Test
    fun delayedReconnectWaitsForCurrentRouteAndAttachmentBeforeDrainingPendingRetries() {
        val fixture = AdmissionFixture(attached = false)
        fixture.activate()
        assertEquals(0, fixture.retries)
        assertTrue(fixture.pending)
        fixture.ready = false
        fixture.deliverServiceIntent()
        assertEquals(
            "Attachment alone cannot override route invalidation",
            0,
            fixture.nativeResumes,
        )
        assertEquals(0, fixture.retries)
        assertTrue(fixture.pending)
        fixture.ready = true
        fixture.activate()
        assertEquals("Only the admitted owner continuation resumes", 1, fixture.nativeResumes)
        assertEquals(1, fixture.retries)
        assertFalse(fixture.pending)
    }

    /** Models only the pinned SDK action switch, not Android service execution. */
    private class AdmissionFixture(attached: Boolean) {
        val lifetime = DownloadServiceLifetime()
        private val queued = mutableListOf<DownloadServiceCommand>()
        var ready = true
        var pending = true
        var retries = 0
        var nativeResumes = 0

        init {
            lifetime.userStarted()
            if (attached) lifetime.serviceStarted()
        }

        fun explicitStart() =
            dispatchExplicitDownloadStart(
                false,
                Download.STATE_QUEUED,
                0,
                dispatch = queued::add,
                userStarted = lifetime::userStarted,
            )

        fun activate() =
            continueDownloadTransfers(
                lifetime,
                ready,
                pending,
                dispatch = queued::add,
                resume = {
                    retries++
                    pending = false
                    nativeResumes++
                },
            )

        fun deliverServiceIntent() {
            val delivery = queued.toList()
            queued.clear()
            if (lifetime.serviceStarted()) activate()
            for (command in delivery) {
                // Media3 1.11.1 DownloadService.java:633-635 INIT does nothing;
                // 658-659 RESUME calls manager.resumeDownloads without owner checks.
                when (command.action) {
                    DownloadService.ACTION_INIT -> Unit
                    DownloadService.ACTION_RESUME_DOWNLOADS -> nativeResumes++
                    else -> fail("Attachment must not queue an ADD")
                }
            }
        }
    }
}
