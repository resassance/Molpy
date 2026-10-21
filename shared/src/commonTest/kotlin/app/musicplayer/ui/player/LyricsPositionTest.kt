package app.musicplayer.ui.player

import app.musicplayer.model.*
import kotlin.test.*

class LyricsPositionTest {
    private val source = SourceRef("lyrics", "entry", "one")

    @Test
    fun syncedLineFollowsSeekAndRespectsExplicitEndGaps() {
        val lyrics =
            NormalizedLyrics(
                null,
                listOf(LyricLine("one", 1000, 5000), LyricLine("two", 10000, null)),
                LyricsKind.LINE_SYNCED,
                source,
            )
        assertNull(LyricsPosition.activeLine(lyrics, 0))
        assertEquals(0, LyricsPosition.activeLine(lyrics, 1000))
        assertNull(LyricsPosition.activeLine(lyrics, 5000))
        assertEquals(1, LyricsPosition.activeLine(lyrics, 15000))
        assertEquals(0, LyricsPosition.activeLine(lyrics, 2000))
        assertNull(LyricsPosition.activeLine(lyrics.copy(kind = LyricsKind.PLAIN), 15000))
        assertNull(LyricsPosition.activeLine(lyrics.copy(lines = emptyList()), 15000))
    }
}
