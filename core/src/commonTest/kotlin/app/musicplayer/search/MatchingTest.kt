package app.musicplayer.search

import kotlin.test.*

class MatchingTest {
    private val matcher = TrackMatcher()
    private val intent = SearchIntent("Linkin Park Numb")
    @Test fun uniqueExactStudioCanLink() {
        val decision = matcher.rank(track(), listOf(track("match")), intent).single()
        assertTrue(decision.autoLink)
        assertTrue(decision.reasons.isNotEmpty())
    }
    @Test fun durationToleranceIncludesBothBoundariesAndRejectsOutside() {
        listOf(50000L to 2000L, 187000L to 3740L).forEach { (duration, tolerance) ->
            listOf(-1L, 1L).forEach { sign ->
                assertTrue(matcher.rank(track(duration = duration), listOf(track("inside", duration = duration + sign * tolerance)), intent).single().autoLink)
                assertFalse(matcher.rank(track(duration = duration), listOf(track("outside", duration = duration + sign * (tolerance + 1))), intent).single().autoLink)
            }
        }
    }
    @Test fun unknownDurationAndUnknownExplicitVersionCannotProveStudio() {
        listOf(track("missing", duration = null), track("unknown", version = "mystery mix"),
            track("zero", duration = 0)).forEach {
            assertFalse(matcher.rank(track(), listOf(it), intent).single().autoLink, it.id.value)
        }
        assertFalse(matcher.rank(track(duration = null), listOf(track("match")), intent).single().autoLink)
    }
    @Test fun rejectsUnrequestedVersionsFromTitleAndCandidateEvidence() {
        listOf("live", "remaster", "cover", "karaoke", "reaction", "tutorial", "full album",
            "slowed + reverb", "acoustic", "instrumental", "remix", "demo", "nightcore", "radio edit").forEach { version ->
            assertFalse(matcher.rank(track(), listOf(track("title", title = "Numb ($version)")), intent).single().autoLink, version)
            assertFalse(matcher.rank(track(), listOf(track("explicit", version = version)), intent).single().autoLink, version)
        }
    }
    @Test fun explicitlyRequestedVersionsReceiveNoVariantPenalty() {
        val liveIntent = SearchIntent("Numb live", setOf("live"))
        val live = track("live", title = "Numb (Live)")
        val decisions = matcher.rank(track(), listOf(track("studio"), live), liveIntent)
        assertEquals(live.id, decisions.first().track.id)
        assertTrue(decisions.first().autoLink)
        assertFalse(decisions.last().autoLink)
        assertTrue(matcher.rank(live, listOf(track("other-live", version = "live")), liveIntent).single().autoLink)
    }
    @Test fun ambiguousTiesCannotLinkAndRankingIsDeterministic() {
        val candidates = listOf(track("b"), track("a"), track("cover", title = "Numb (Cover)"))
        val decisions = matcher.rank(track(), candidates, intent)
        assertTrue(decisions.all { !it.autoLink })
        assertEquals(decisions, matcher.rank(track(), candidates.reversed(), intent))
        assertEquals("cover", decisions.last().track.id.value)
    }
    @Test fun duplicatedSameSourceIsOneCandidateRatherThanAmbiguous() {
        val one = track("one")
        assertTrue(matcher.rank(track(), listOf(one, one), intent).single().autoLink)
    }
    @Test fun unrelatedTitleArtistOrContradictoryAlbumNeverLinks() {
        listOf(track("title", title = "In the End"), track("artist", artist = "Other Band"),
            track("album", album = "Other Album")).forEach {
            assertFalse(matcher.rank(track(album = "Meteora"), listOf(it), intent).single().autoLink)
        }
        assertTrue(matcher.rank(track(album = "Meteora"), listOf(track("missing-album")), intent).single().autoLink)
    }
    @Test fun unicodeCyrillicFeaturedArtistsAndRealNamesMatch() {
        val reference = track(title = "Группа крови", artist = "Кино")
        assertTrue(matcher.rank(reference, listOf(track("cyrillic", title = "ГРУППА КРОВИ [Official Video]", artist = "КИНО")), SearchIntent("Группа крови")).single().autoLink)
        assertTrue(matcher.rank(track(title = "Numb (feat. Guest)"), listOf(track("feat")), intent).single().autoLink)
        assertTrue(matcher.rank(track(title = "Live Forever"), listOf(track("real-title", title = "Live Forever")), SearchIntent("Live Forever")).single().autoLink)
    }
    @Test fun exactTitleRanksAboveReorderedWords() {
        val reference = track(title = "Hello Goodbye")
        val decisions = matcher.rank(reference, listOf(track("a-reordered", title = "Goodbye Hello"),
            track("z-exact", title = "Hello Goodbye")), SearchIntent("Hello Goodbye"))
        assertEquals("z-exact", decisions.first().track.id.value)
        assertTrue(decisions.first().autoLink)
        assertFalse(decisions.last().autoLink)
    }
    @Test fun noisyVersionSuffixCannotLinkToStudio() {
        listOf("Numb - Live [Official Video]", "Numb - Remastered [HD]").forEach { title ->
            assertFalse(matcher.rank(track(title = title), listOf(track("studio")), intent).single().autoLink, title)
        }
    }
    @Test fun genuineSubtitleCannotLinkToLiveVersion() {
        assertFalse(matcher.rank(track(title = "Song (We Live Forever)"), listOf(track("live", title = "Song (Live)")),
            SearchIntent("Song")).single().autoLink)
        assertTrue(matcher.rank(track(title = "Long Live"), listOf(track("same", title = "Long Live")), SearchIntent("Long Live")).single().autoLink)
    }
    @Test fun differingRecordingDescriptorsCannotAutoLink() {
        listOf("Remaster 2011" to "Remaster 2009", "Live at North" to "Live at South").forEach { (left, right) ->
            val versionIntent = SearchIntent("Song", setOf(if (left.startsWith("Live")) "live" else "remaster"))
            assertFalse(matcher.rank(track(title = "Song ($left)"), listOf(track("different", title = "Song ($right)")), versionIntent).single().autoLink)
            assertFalse(matcher.rank(track(title = "Song", version = left), listOf(track("explicit", title = "Song", version = right)), versionIntent).single().autoLink)
            assertTrue(matcher.rank(track(title = "Song ($left)"), listOf(track("same", title = "Song ($left)")), versionIntent).single().autoLink)
        }
        assertFalse(matcher.rank(track(), listOf(track("unknown", version = "mystery live mix")), SearchIntent("Numb live", setOf("live"))).single().autoLink)
    }
    @Test fun fuzzyResultsRemainVisibleWithoutAutoLink() {
        val decisions = matcher.rank(track(), listOf(track("fuzzy", title = "Numb acoustic session"), track("exact")), intent)
        assertEquals("exact", decisions.first().track.id.value)
        assertTrue(decisions.first().autoLink)
        assertFalse(decisions.last().autoLink)
    }
}
