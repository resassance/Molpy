package app.musicplayer.lyrics

import app.musicplayer.model.*
import kotlin.test.*

class LrcParserTest {
    private val parser = LrcParser()

    @Test fun repeatedTimestampTagsAndIdenticalLinesAreRetainedInOrder() {
        val lines = assertNotNull(parser.parse("[00:00.00]Echo\n[00:00.00]Echo\n[00:01.20][00:02.400]Again", 3000).valueOrNull())
        assertEquals(listOf(0L, 0L, 1200L, 2400L), lines.map { it.startMs })
        assertEquals(listOf("Echo", "Echo", "Again", "Again"), lines.map { it.text })
        assertEquals(listOf(0L, 1200L, 2400L, 3000L), lines.map { it.endMs })
    }

    @Test fun metadataIsIgnoredAndUnknownDurationLeavesFinalEndOpen() {
        val lines = assertNotNull(parser.parse("[ar:Synthetic]\n[00:00]One\n[00:01.2]Two", null).valueOrNull())
        assertEquals(listOf(0L, 1200L), lines.map { it.startMs })
        assertNull(lines.last().endMs)
    }

    @Test fun malformedNegativeDecreasingAndBeyondDurationTimingAreRejected() {
        for (text in listOf("[00:xx]bad", "[-00:01]bad", "[00:61]bad", "[00:02]later\n[00:01]earlier", "[00:04]late", "[00:01]good\nuntimed", "[offset:-500]\n[00:01]line")) {
            assertIs<AppResult.Failure>(parser.parse(text, 3000), text)
        }
    }

    @Test fun plainOrEmptyTextDoesNotMasqueradeAsSynchronizedLyrics() {
        assertIs<AppResult.Failure>(parser.parse("One\nTwo", null))
        assertIs<AppResult.Failure>(parser.parse("", null))
    }
}
