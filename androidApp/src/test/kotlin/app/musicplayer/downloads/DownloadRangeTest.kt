package app.musicplayer.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadRangeTest {
    @Test
    fun probeOnlyAcceptsExactSingleByteResponse() {
        assertEquals(1000L, downloadProbeLength(206, "bytes 0-0/1000", "1"))
        assertNull(downloadProbeLength(206, "bytes 0-99/1000", "100"))
        assertNull(downloadProbeLength(403, null, "1000"))
        assertNull(downloadProbeLength(200, null, "1000"))
        assertNull(downloadProbeLength(206, "bytes 0-0/*", "1"))
    }

    @Test
    fun signedRangeIsNotRewritten() {
        val uri = "https://r1.googlevideo.com/videoplayback?range=0-99&sparams=range%2Cexpire&sig=x"
        assertEquals(uri, downloadRangeUri(uri, 100, -1, 1000))
        val duplicate =
            "https://r1.googlevideo.com/videoplayback?sparams=expire&sparams=range&range=0-99"
        assertEquals(duplicate, downloadRangeUri(duplicate, 100, -1, 1000))
    }

    @Test
    fun knownGooglevideoLengthUsesExactRemainingRange() {
        assertEquals(
            "https://r1.googlevideo.com/videoplayback?sig=x&range=0-999",
            downloadRangeUri("https://r1.googlevideo.com/videoplayback?sig=x", 0, -1, 1000),
        )
        assertEquals(
            "https://r1.googlevideo.com/videoplayback?sig=x&range=400-599",
            downloadRangeUri("https://r1.googlevideo.com/videoplayback?sig=x", 400, 200, 1000),
        )
    }

    @Test
    fun unknownLengthAndForeignHostsAreUntouched() {
        for (uri in
            listOf(
                "https://example.org/a",
                "https://notgooglevideo.com/a",
                "https://googlevideo.com.evil.org/a",
            )) assertEquals(uri, downloadRangeUri(uri, 0, -1, 1000))
        val uri = "https://r1.googlevideo.com/videoplayback?sig=x"
        assertEquals(uri, downloadRangeUri(uri, 0, -1, null))
    }

    @Test
    fun existingRangesAreReplacedAndBoundsDoNotOverflow() {
        val uri = "https://r1.googlevideo.com/videoplayback?range=0-99&sig=x"
        assertEquals(
            "https://r1.googlevideo.com/videoplayback?sig=x&range=100-999",
            downloadRangeUri(uri, 100, Long.MAX_VALUE, 1000),
        )
    }
}
