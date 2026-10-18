package app.musicplayer.playback

import app.musicplayer.model.*
import kotlin.test.*

class StreamLeaseCacheTest {
    private val id = CandidateId("candidate")
    private fun lease(expiry: Long? = 100_000, generation: Long = 7) = ResolvedPlayback(
        id, Representation("140", "audio/mp4", "mp4a.40.2", 1000, 10000, "etag"),
        TransportKind.PROGRESSIVE,
        MediaRequest("https://media.invalid/audio?signature=test-secret", mapOf("Authorization" to "secret-header")),
        expiry, generation,
    )

    @Test fun requiresThirtySecondsRemaining() {
        val cache = StreamLeaseCache()
        cache.put(lease())
        assertNotNull(cache.get(id, "140", 7, 69_999))
        assertNull(cache.get(id, "140", 7, 70_000))
        assertNull(cache.get(id, "140", 7, 70_001))
    }

    @Test fun generationMismatchInvalidatesOldLease() {
        val cache = StreamLeaseCache()
        cache.put(lease())
        assertNull(cache.get(id, "140", 8, 1))
        assertNull(cache.get(id, "140", 7, 1))
    }

    @Test fun unknownExpiryNeverReusedForNewSession() {
        val cache = StreamLeaseCache()
        cache.put(lease(expiry = null))
        assertNull(cache.get(id, "140", 7, 0))
    }

    @Test fun keysIncludeCandidateAndRepresentationAndClearRemovesLeases() {
        val cache = StreamLeaseCache()
        cache.put(lease())
        assertNull(cache.get(CandidateId("other"), "140", 7, 0))
        assertNull(cache.get(id, "251", 7, 0))
        assertNotNull(cache.get(id, "140", 7, 0))
        cache.clear()
        assertNull(cache.get(id, "140", 7, 0))
    }

    @Test fun diagnosticStringsRedactRequestsIncludingCopiedLeases() {
        val resolved = lease()
        for (value in listOf(resolved, resolved.copy(), resolved.request)) {
            assertFalse(value.toString().contains("signature=test-secret"))
            assertFalse(value.toString().contains("secret-header"))
            assertFalse(value.toString().contains("media.invalid"))
        }
        assertTrue(resolved.request.uri.contains("signature=test-secret"))
    }

    @Test fun expiryArithmeticDoesNotOverflow() {
        val cache = StreamLeaseCache()
        cache.put(lease(expiry = Long.MAX_VALUE))
        assertNotNull(cache.get(id, "140", 7, 0))
        assertNull(cache.get(id, "140", 7, Long.MAX_VALUE - 1))
    }
}
