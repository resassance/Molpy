package app.musicplayer.lyrics

import app.musicplayer.model.*
import kotlin.test.*

class LyricsSelectionTest {
    private val source = SourceRef("synthetic", "lyrics", "one")
    private val plain = NormalizedLyrics("Right words", emptyList(), LyricsKind.PLAIN, source)
    private val synced = NormalizedLyrics("Right words", listOf(LyricLine("Right words", 0, 1000)), LyricsKind.LINE_SYNCED, source)
    private val selector = LyricsSelector()

    @Test fun correctPlainBeatsWrongVersionSynced() {
        assertEquals(plain, selector.select(listOf(LyricsCandidate(synced, false, true), LyricsCandidate(plain, true, false))))
    }

    @Test fun suitableSyncedBeatsSuitablePlainRegardlessOfProviderOrder() {
        val a = LyricsCandidate(plain, true, false)
        val b = LyricsCandidate(synced, true, true)
        assertEquals(synced, selector.select(listOf(a, b)))
        assertEquals(synced, selector.select(listOf(b, a)))
    }

    @Test fun invalidTimingFallsBackToPlainWithNoTimedLines() {
        assertEquals(plain, selector.select(listOf(LyricsCandidate(synced, true, false))))
    }

    @Test fun noMatchingVersionDoesNotReturnOtherRecording() {
        assertNull(selector.select(listOf(LyricsCandidate(synced, false, true))))
        assertNull(selector.select(emptyList()))
    }
}
