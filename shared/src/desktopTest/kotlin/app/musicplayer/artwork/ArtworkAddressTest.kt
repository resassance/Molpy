package app.musicplayer.artwork

import kotlin.test.*

class ArtworkAddressTest {
    @Test
    fun musicArtworkRequestsEnoughPixelsAndPreservesCropFlagsAndQuery() {
        val url = "https://lh3.googleusercontent.com/cover=w60-h60-l90-rj?key=value"
        assertEquals(
            "https://lh3.googleusercontent.com/cover=w640-h640-l90-rj?key=value",
            artworkAddress(url, 600),
        )
        assertEquals(
            "https://yt3.ggpht.com/cover=s320-c-k-rj",
            artworkAddress("https://yt3.ggpht.com/cover=s88-c-k-rj", 280),
        )
        assertEquals(
            "https://yt3.googleusercontent.com/cover=w640-h320-p-rj",
            artworkAddress("https://yt3.googleusercontent.com/cover=w100-h50-p-rj", 600),
        )
    }

    @Test
    fun resizingLeavesOriginalsAndUnrecognizedAddressesAlone() {
        val original = "https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj"
        assertEquals(original, artworkAddress(original, 280))
        for (url in
            listOf(
                "https://images.test/cover=w60-h60",
                "https://lh3.googleusercontent.com.evil.test/cover=w60-h60",
                "https://i.ytimg.com/vi/id/default.jpg",
                "http://lh3.googleusercontent.com/cover=w60-h60",
                "https://lh3.googleusercontent.com/cover?size=w60-h60",
                "https://lh3.googleusercontent.com/cover=w0-h0",
            )) assertEquals(url, artworkAddress(url, 600))
    }

    @Test
    fun requestedSizeIsBounded() {
        val url = "https://lh3.googleusercontent.com/cover=w60-h60-l90-rj"
        assertEquals(url, artworkAddress(url, 0))
        assertEquals(
            "https://lh3.googleusercontent.com/cover=w1280-h1280-l90-rj",
            artworkAddress(url, Int.MAX_VALUE),
        )
    }
}
