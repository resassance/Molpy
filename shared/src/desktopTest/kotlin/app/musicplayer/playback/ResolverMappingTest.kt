package app.musicplayer.playback

import app.musicplayer.model.*
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

class ResolverMappingTest {
    private val candidate = PlaybackCandidate(CandidateId("candidate"), SourceRef("youtube", "video", "video"), "", 120_000, MatchConfidence.HIGH)
    private val network = NetworkContext(7, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
    private val progressive = PlaybackCapabilities(setOf(TransportKind.PROGRESSIVE))
    private var now = 1_000L
    private var generation = 7L
    private fun stream(expiry: Long? = 100_000) = ExtractedStream(
        videoId = "video", audioUrl = "https://media.invalid/audio?signature=test-secret",
        headers = mapOf("User-Agent" to "test-agent"), loudnessDb = null,
        expiresAt = expiry?.let(Instant::fromEpochMilliseconds), contentLengthBytes = 1234,
        itag = 140, mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128000,
        sampleRate = 44100, clientName = "WEB", profileId = "WEB", requireBoundedRange = false,
        rangeChunkSizeBytes = 1_048_576,
    )

    private class FakeExtractor(private val action: suspend (ContentHints) -> ExtractedStream?) : StreamExtractor {
        var calls = 0
        override suspend fun prewarm() = error("Resolver must not prewarm")
        override suspend fun extract(videoId: String, hints: ContentHints, excludedClients: Set<String>, audioQuality: AudioQuality, clientPlaybackNonce: String): ExtractedStream? {
            calls++
            return action(hints)
        }
    }
    private fun resolver(extractor: StreamExtractor, diagnostics: (ResolverDiagnostic) -> Unit = {}) =
        InnerTubePlaybackResolver(extractor, { now }, { generation }, diagnostics)

    @Test fun directStreamMapsRuntimeRequestAndRepresentation() = runTest {
        val result = resolver(FakeExtractor { stream() }).resolve(candidate, progressive, network).valueOrNull()!!
        assertEquals(candidate.id, result.candidateId)
        assertEquals(TransportKind.PROGRESSIVE, result.transport)
        assertEquals("140", result.representation.id)
        assertEquals("audio/mp4", result.representation.mimeType)
        assertEquals("mp4a.40.2", result.representation.codec)
        assertEquals(1234L, result.representation.expectedBytes)
        assertNull(result.representation.expectedDurationMs, "Catalog duration is not measured media duration")
        assertEquals("test-agent", result.request.headers["User-Agent"])
        assertEquals(100_000L, result.expiresAtMs)
        assertEquals(7L, result.networkGeneration)
        assertFalse(result.toString().contains("test-secret"))
    }

    @Test fun advertisedCapabilitiesConstrainExtractorWithoutPremiumOrVideo() = runTest {
        val extractor = FakeExtractor { hints ->
            assertFalse(hints.allowHls)
            assertFalse(hints.allowSabr)
            assertFalse(hints.allowBoundedRange)
            assertFalse(hints.premium)
            assertFalse(hints.wantVideo)
            stream()
        }
        assertNotNull(resolver(extractor).resolve(candidate, progressive, network).valueOrNull())
    }

    @Test fun hlsRequiresAdvertisedCapabilityAndAllowsMissingCodec() = runTest {
        val extractor = FakeExtractor { stream().copy(mimeType = "application/x-mpegURL", codecs = null, itag = 96) }
        assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, resolver(extractor).resolve(candidate, progressive, network).errorOrNull()?.code)
        val hls = resolver(FakeExtractor { hints ->
            assertTrue(hints.allowHls)
            stream().copy(mimeType = "application/x-mpegURL", codecs = null, itag = 96)
        }).resolve(candidate, PlaybackCapabilities(setOf(TransportKind.HLS)), network).valueOrNull()!!
        assertEquals(TransportKind.HLS, hls.transport)
        assertEquals("", hls.representation.codec)
    }

    @Test fun boundedRangeStaysDisabledEvenIfCallerAdvertisesIt() = runTest {
        for (chunked in listOf(false, true)) {
            val extractor = FakeExtractor { hints ->
                assertFalse(hints.allowBoundedRange)
                stream().copy(requireBoundedRange = !chunked, useRangeChunks = chunked)
            }
            assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, resolver(extractor).resolve(candidate,
                PlaybackCapabilities(TransportKind.entries.toSet()), network).errorOrNull()?.code)
        }
    }

    @Test fun sabrBootstrapIsNeverMappedAsOrdinaryMediaUrl() = runTest {
        val bootstrap = SabrBootstrap("video", "https://media.invalid/sabr", byteArrayOf(), 1, "1",
            SabrFormatId(140), SabrFormatId(137), 1080, durationMs = 10000,
            contentLengthBytes = 1234, mimeType = "audio/mp4")
        for (output in listOf(stream().copy(sabrBootstrap = bootstrap), stream().copy(audioUrl = "sabr://video"))) {
            assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, resolver(FakeExtractor { output }).resolve(candidate, progressive, network).errorOrNull()?.code)
        }
    }

    @Test fun progressiveOutputCannotSatisfyHlsOnlyCaller() = runTest {
        assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, resolver(FakeExtractor { stream() }).resolve(candidate,
            PlaybackCapabilities(setOf(TransportKind.HLS)), network).errorOrNull()?.code)
    }

    @Test fun nearExpiryRefreshesOnceUsingInjectedClock() = runTest {
        var attempts = 0
        val extractor = FakeExtractor { if (++attempts == 1) stream(now + 29_999) else stream(now + 31_000) }
        assertEquals(now + 31_000, resolver(extractor).resolve(candidate, progressive, network).valueOrNull()?.expiresAtMs)
        assertEquals(2, extractor.calls)
    }

    @Test fun expiryRefreshBudgetStopsAfterSecondUnusableLease() = runTest {
        val extractor = FakeExtractor { stream(now + 30_000) }
        assertEquals(ErrorCode.URL_REJECTED, resolver(extractor).resolve(candidate, progressive, network).errorOrNull()?.code)
        assertEquals(2, extractor.calls)
    }

    @Test fun unknownExpiryAllowsImmediateUseWithoutRetry() = runTest {
        val extractor = FakeExtractor { stream(null) }
        assertNotNull(resolver(extractor).resolve(candidate, progressive, network).valueOrNull())
        assertEquals(1, extractor.calls)
    }

    @Test fun generationChangeDuringExtractionRejectsLateResult() = runTest {
        val extractor = FakeExtractor { generation++; stream() }
        assertEquals(ErrorCode.INVALID_CONFIG, resolver(extractor).resolve(candidate, progressive, network).errorOrNull()?.code)
    }

    @Test fun alreadyStaleGenerationDoesNotExtract() = runTest {
        generation = 8
        val extractor = FakeExtractor { error("Must not extract") }
        assertEquals(ErrorCode.INVALID_CONFIG, resolver(extractor).resolve(candidate, progressive, network).errorOrNull()?.code)
        assertEquals(0, extractor.calls)
    }

    @Test fun transportIoFailureIsRetryableNetworkFailure() = runTest {
        val result = resolver(FakeExtractor { throw java.io.IOException("secret-address") }).resolve(candidate, progressive, network)
        assertEquals(ErrorCode.NETWORK, result.errorOrNull()?.code)
        assertTrue(result.errorOrNull()!!.retryable)
        assertFalse(result.toString().contains("secret-address"))
    }

    @Test fun sourceNetworkAndEmptyCapabilityFailuresDoNotExtract() = runTest {
        val extractor = FakeExtractor { error("Must not extract") }
        val resolver = resolver(extractor)
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, resolver.resolve(candidate.copy(source = SourceRef("other", "video", "video")), progressive, network).errorOrNull()?.code)
        assertEquals(ErrorCode.NETWORK, resolver.resolve(candidate, progressive, network.copy(online = false)).errorOrNull()?.code)
        assertEquals(ErrorCode.INVALID_CONFIG, resolver.resolve(candidate, progressive, network.copy(ready = false)).errorOrNull()?.code)
        assertEquals(ErrorCode.PROXY_UNSUPPORTED, resolver.resolve(candidate, progressive, network.copy(config = NetworkConfig(NetworkMode.SOCKS5, ProxyEndpoint("localhost", 9050), null))).errorOrNull()?.code)
        assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, resolver.resolve(candidate, PlaybackCapabilities(emptySet()), network).errorOrNull()?.code)
        assertEquals(0, extractor.calls)
    }

    @Test fun nullAndTypedProviderFailuresAreNormalizedWithoutRawMessages() = runTest {
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, resolver(FakeExtractor { null }).resolve(candidate, progressive, network).errorOrNull()?.code)
        val mappings = mapOf(StreamResolveException.Reason.NETWORK to ErrorCode.NETWORK,
            StreamResolveException.Reason.AGE_RESTRICTED to ErrorCode.LOGIN_REQUIRED,
            StreamResolveException.Reason.UNAVAILABLE to ErrorCode.SOURCE_UNAVAILABLE,
            StreamResolveException.Reason.NO_PLAYABLE_STREAM to ErrorCode.SOURCE_UNAVAILABLE,
            StreamResolveException.Reason.EXPLICIT_UNSUPPORTED to ErrorCode.SOURCE_UNAVAILABLE,
            StreamResolveException.Reason.NO_MUSIC_VIDEO to ErrorCode.SOURCE_UNAVAILABLE,
            StreamResolveException.Reason.UNKNOWN to ErrorCode.PROVIDER_CHANGED)
        for ((reason, expected) in mappings) {
            val result = resolver(FakeExtractor { throw StreamResolveException(reason, "signature=test-secret") }).resolve(candidate, progressive, network)
            assertEquals(expected, result.errorOrNull()?.code)
            assertFalse(result.toString().contains("test-secret"))
        }
    }

    @Test fun tokenRequiredUsesSafeTypedDiagnostics() = runTest {
        val events = mutableListOf<ResolverDiagnostic>()
        val upstream = StreamDiagnostics(listOf(StreamAttemptDiagnostic("secret-client", null, "secret-agent", "po_token_unavailable")), false)
        val result = resolver(FakeExtractor {
            throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "PO-token provider unavailable", diagnostics = upstream)
        }, events::add).resolve(candidate, progressive, network)
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, result.errorOrNull()?.code)
        assertEquals(ResolverStage.TOKEN, events.single().stage)
        assertEquals(ResolverReason.TOKEN_REQUIRED, events.single().reason)
        assertEquals(ResolverVersion.INNERTUBEX_0_10_0, events.single().version)
        assertFalse(events.toString().contains("secret-"))
    }

    @Test fun typedAccessRestrictionTakesPrecedenceOverAnUnsuccessfulTokenAttempt() = runTest {
        val upstream = StreamDiagnostics(listOf(StreamAttemptDiagnostic("WEB", null, "agent", "po_token_unavailable")), false)
        val result = resolver(FakeExtractor {
            throw StreamResolveException(StreamResolveException.Reason.AGE_RESTRICTED, "restricted", diagnostics = upstream)
        }).resolve(candidate, progressive, network)
        assertEquals(ErrorCode.LOGIN_REQUIRED, result.errorOrNull()?.code)
    }

    @Test fun cancellationPropagatesAndUnexpectedFailureIsRedacted() = runTest {
        assertFailsWith<CancellationException> {
            resolver(FakeExtractor { throw CancellationException("cancel") }).resolve(candidate, progressive, network)
        }
        val result = resolver(FakeExtractor { error("secret-token") }).resolve(candidate, progressive, network)
        assertEquals(ErrorCode.UNKNOWN, result.errorOrNull()?.code)
        assertFalse(result.toString().contains("secret-token"))
    }

    @Test fun malformedAndCrossVideoOutputsAreRejected() = runTest {
        for (output in listOf(stream().copy(audioUrl = "file:///private/audio"), stream().copy(videoId = "other"), stream().copy(mimeType = null))) {
            assertEquals(ErrorCode.PROVIDER_CHANGED, resolver(FakeExtractor { output }).resolve(candidate, progressive, network).errorOrNull()?.code)
        }
    }

    @Test fun constructionAndCloseDoNotRequestNetworkOrCloseBorrowedHttpClient() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; respond("ok") })
        val pipeline = ExtractionFactory.create(http, nowMs = { now }, currentNetworkGeneration = { generation })
        assertEquals(0, requests)
        pipeline.close()
        assertEquals(0, requests)
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, pipeline.resolver.resolve(candidate, progressive, network).errorOrNull()?.code)
        assertEquals(0, requests)
        http.get("https://example.invalid/owned-by-caller")
        assertEquals(1, requests)
        http.close()
    }
}
