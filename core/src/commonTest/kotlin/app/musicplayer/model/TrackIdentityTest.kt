package app.musicplayer.model

import app.musicplayer.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TrackIdentityTest {
    @Test fun metadataAndPlaybackAreIndependent() {
        val track = Fixtures.track()
        assertEquals("youtube_music", track.provenance.source.provider)
        assertEquals("youtube", track.candidates.single().source.provider)
        assertNotEquals(track.id.value, track.candidates.single().source.id)
        val changed = track.copy(candidates = listOf(track.candidates.single().copy(source = SourceRef("youtube", "video", "v2"))))
        assertEquals(track.id, changed.id)
        assertEquals(track.provenance, changed.provenance)
        assertEquals(track.externalIds, changed.externalIds)
    }

    @Test fun rejectsUrlAsSourceId() {
        assertFailsWith<IllegalArgumentException> {
            SourceRef("youtube", "video", "https://cdn.invalid/audio?sig=test")
        }
    }
}

class ModelValidationTest {
    @Test fun rejectsBlankAppIds() {
        listOf<(String) -> Any>(::TrackId, ::CandidateId, ::QueueEntryId, ::AssetId, ::SecretId).forEach { wrap ->
            assertFailsWith<IllegalArgumentException> { wrap(" \t") }
        }
    }

    @Test fun rejectsSourceUrlsInOtherForms() {
        listOf("HTTP://example.invalid/audio", "//example.invalid/audio", "file:///audio", "content://media/1").forEach {
            assertFailsWith<IllegalArgumentException> { SourceRef("youtube", "video", it) }
        }
    }

    @Test fun keepsMissingMetadataWithoutInventingNames() {
        val track = Fixtures.track(title = "", artist = "", durationMs = null)
        assertEquals("", track.title)
        assertEquals("", track.artists.single().name)
        assertEquals(null, track.durationMs)
        assertEquals(null, track.album)
    }

    @Test fun networkModesRejectUnsupportedCombinations() {
        val endpoint = ProxyEndpoint("proxy.invalid", 8080)
        assertFailsWith<IllegalArgumentException> { NetworkConfig(NetworkMode.SYSTEM, endpoint, null) }
        assertFailsWith<IllegalArgumentException> { NetworkConfig(NetworkMode.SYSTEM, null, SecretId("s1")) }
        assertFailsWith<IllegalArgumentException> { NetworkConfig(NetworkMode.HTTP_CONNECT, null, null) }
        assertFailsWith<IllegalArgumentException> { NetworkConfig(NetworkMode.SOCKS5, endpoint, SecretId("s1")) }
        assertFailsWith<IllegalArgumentException> { ProxyEndpoint("proxy.invalid", 0) }
        assertFailsWith<IllegalArgumentException> { ProxyEndpoint("proxy.invalid", 65536) }
        assertFailsWith<IllegalArgumentException> { ProxyEndpoint(" ", 8080) }
    }

    @Test fun appliedConfigurationAndConnectivityAreIndependent() {
        val system = NetworkConfig(NetworkMode.SYSTEM, null, null)
        val context = NetworkContext(1, system, ready = true, online = false)
        assertEquals(true, context.ready)
        assertEquals(false, context.online)
        val endpoint = ProxyEndpoint("proxy.invalid", 8080)
        assertEquals(SecretId("s1"), NetworkConfig(NetworkMode.HTTP_CONNECT, endpoint, SecretId("s1")).credentialRef)
        assertEquals(endpoint, NetworkConfig(NetworkMode.SOCKS5, endpoint, null).endpoint)
    }

    @Test fun resultsDistinguishSuccessfulAbsenceAndFailure() {
        val absent: AppResult<String?> = AppResult.Success(null)
        val error = AppError(ErrorCode.DATABASE, "operation-1", retryable = true)
        val failure: AppResult<String?> = AppResult.Failure(error)
        assertEquals(null, absent.valueOrNull())
        assertEquals(null, absent.errorOrNull())
        assertEquals(error, failure.errorOrNull())
        assertEquals(null, failure.valueOrNull())
        assertEquals("value", AppResult.Success("value").valueOrNull())
    }

    @Test fun exceptionMessageContainsOnlySafeCode() {
        val error = AppError(ErrorCode.DATABASE, "private-operation-detail", retryable = false)
        val exception = AppFailureException(error)
        assertEquals("DATABASE", exception.message)
        assertEquals(error, exception.error)
    }
}
