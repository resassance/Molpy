package app.musicplayer.playback

import app.musicplayer.model.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSourceMimeTypeTest {
    @Test fun normalizedHlsTransportUsesCanonicalMedia3MimeForEveryAcceptedAlias() {
        listOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
            "application/vnd.apple.mpegurl; charset=utf-8", "application/x-mpegURL")
            .forEach { alias -> assertEquals(alias, "application/x-mpegURL", media3MimeType(TransportKind.HLS, alias)) }
    }
    @Test fun progressiveRetainsItsActualRepresentationMime() {
        assertEquals("audio/wav", media3MimeType(TransportKind.PROGRESSIVE, "audio/wav"))
        assertEquals("audio/mp4; codecs=mp4a.40.2", media3MimeType(TransportKind.PROGRESSIVE, "audio/mp4; codecs=mp4a.40.2"))
    }
}
