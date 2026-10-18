package app.musicplayer.search

import kotlin.test.*

class NormalizationTest {
    private val normalizer = TrackNormalizer()
    @Test fun normalizesPunctuationUnicodeCyrillicAndNoise() {
        listOf(
            "Numb [Official Video]" to "numb",
            "Numb (Lyrics) [HD]" to "numb",
            "  ГРУППА — КРОВИ!  " to "группа крови",
            "Beyoncé – Déjà Vu" to "beyonce deja vu",
            "Beyonce\u0301 – De\u0301ja\u0300 Vu" to "beyonce deja vu",
            "Don’t Stop" to "don t stop",
            "Live Forever" to "live forever",
            "Cover Me" to "cover me",
            "Song (A Real Subtitle)" to "song a real subtitle",
            "Numb (feat. Guest)" to "numb",
            "Numb (Live at Wembley)" to "numb live at wembley",
            "Numb - 2011 Remastered" to "numb 2011 remastered",
        ).forEach { (input, expected) -> assertEquals(expected, normalizer.normalizeTitle(input), input) }
        assertEquals("кино", normalizer.normalizeArtist(" КИНО "))
        assertEquals("linkin park", normalizer.normalizeArtist("Linkin Park feat. Guest"))
        assertEquals("linkin park", normalizer.normalizeArtist("Linkin Park - Topic"))
    }
    @Test fun serviceNoiseAfterVersionRetainsEvidence() {
        listOf("Numb - Live [Official Video]" to "live", "Numb - Remastered [HD]" to "remaster").forEach { (title, marker) ->
            assertEquals(setOf(marker), normalizer.versionMarkers(title), title)
            assertEquals("numb", normalizer.normalizeTitle(title), title)
        }
    }
    @Test fun genuineSuffixesAndSubtitlesAreNotVersionAnnotations() {
        listOf("Long Live" to "long live", "Long Live [Official Video]" to "long live",
            "Song (We Live Forever)" to "song we live forever", "Song (Live Forever)" to "song live forever").forEach { (title, key) ->
            assertEquals(key, normalizer.normalizeTitle(title), title)
            assertEquals(emptySet(), normalizer.versionMarkers(title), title)
        }
    }
    @Test fun distinguishingVersionDetailsRemainInComparisonKeys() {
        assertEquals("song remaster 2011", normalizer.normalizeTitle("Song (Remaster 2011) [HD]"))
        assertNotEquals(normalizer.normalizeTitle("Song (Remaster 2011)"), normalizer.normalizeTitle("Song (Remaster 2009)"))
        assertNotEquals(normalizer.normalizeTitle("Song (Live at North)"), normalizer.normalizeTitle("Song (Live at South)"))
        assertEquals(setOf("remaster"), normalizer.versionMarkers("Remaster 2011"))
        assertEquals(setOf("live"), normalizer.versionMarkers("Live at North"))
        assertEquals(emptySet(), normalizer.versionMarkers("mystery live mix"))
    }
    @Test fun featuredArtistCleanupDoesNotEraseFollowingRecordingDetails() {
        assertEquals("song remaster 2011", normalizer.normalizeTitle("Song (feat. Guest) (Remaster 2011)"))
        assertNotEquals(normalizer.normalizeTitle("Song feat. Guest (Remaster 2011)"),
            normalizer.normalizeTitle("Song feat. Guest (Remaster 2009)"))
    }
    @Test fun contextualMarkersPreserveActualTitlesAndCompoundVersions() {
        assertEquals(emptySet(), normalizer.versionMarkers("Live Forever"))
        assertEquals(emptySet(), normalizer.versionMarkers("Cover Me"))
        assertEquals(setOf("slowed", "reverb"), normalizer.versionMarkers("Song (slowed + reverb)"))
        assertEquals(setOf("live"), normalizer.versionMarkers("Song (Live at Wembley)"))
        listOf("live", "remaster", "cover", "karaoke", "reaction", "tutorial", "full album",
            "slowed", "reverb", "acoustic", "instrumental", "remix", "demo", "nightcore", "radio edit")
            .forEach { assertEquals(setOf(it), normalizer.versionMarkers("Song [$it]"), it) }
        assertEquals(setOf("remaster"), normalizer.versionMarkers("Song - 2011 Remastered"))
    }
}
