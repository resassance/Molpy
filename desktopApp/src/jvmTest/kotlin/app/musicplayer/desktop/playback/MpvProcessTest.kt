package app.musicplayer.desktop.playback

import kotlin.test.*

class MpvProcessTest {
    @Test
    fun unknownOrOldPlayerVersionIsRejectedBeforeSendingMedia() {
        assertTrue(MpvProcess.supportedVersion("mpv v0.41.0"))
        assertTrue(MpvProcess.supportedVersion("mpv v0.42.0-git"))
        assertFalse(MpvProcess.supportedVersion("mpv v0.38.0"))
        assertFalse(MpvProcess.supportedVersion("unknown"))
    }

    @Test
    fun processArgumentsAreIsolatedAndPreserveSpaces() {
        val args = MpvProcess.arguments("C:/Program Files/mpv/mpv.exe", "private-pipe")
        assertEquals("C:/Program Files/mpv/mpv.exe", args.first())
        assertTrue("--no-config" in args)
        assertTrue("--ytdl=no" in args)
        assertTrue("--no-video" in args)
        assertTrue(args.none { it.contains("http://") || it.contains("https://") })
    }
}
