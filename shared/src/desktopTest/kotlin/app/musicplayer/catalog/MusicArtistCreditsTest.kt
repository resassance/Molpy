package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class MusicArtistCreditsTest {
    private fun fixture() =
        requireNotNull(javaClass.getResource("/catalog/artist-credits/plain.json")).readText()

    private suspend fun map(body: String) =
        assertNotNull(CatalogMapper(SessionIdentityStore()) { 0L }.mapSongs(body).valueOrNull())
            .single()

    @Test
    fun plainCreditSurvivesWithoutProfileLink() = runTest {
        val track = map(fixture())
        assertEquals(listOf("Linkin Park"), track.artists.map { it.name })
        assertNull(track.artists.single().source)
        assertEquals("Meteora", track.album?.name)
        assertEquals(187000L, track.durationMs)
    }

    @Test
    fun plainAndLinkedCollaboratorsKeepTheirOrder() = runTest {
        val body =
            fixture()
                .replace(
                    "\"text\": \"Linkin Park\"",
                    """"text": "Linkin Park"},{"text": " & "},{"text": "Guest", "navigationEndpoint":{"browseEndpoint":{"browseId":"guest","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}""",
                )
        val track = map(body)
        assertEquals(listOf("Linkin Park", "Guest"), track.artists.map { it.name })
        assertNull(track.artists.first().source)
        assertEquals(SourceRef("youtube", "artist", "guest"), track.artists.last().source)
    }

    @Test
    fun albumAndDurationAreNotInventedArtists() = runTest {
        val body = fixture().replace("\"text\": \"Linkin Park\"", "\"text\": \"3:33\"")
        assertTrue(map(body).artists.isEmpty())
    }

    @Test
    fun resultTypeIsNotAnArtist() = runTest {
        val body = fixture().replace("\"text\": \"Linkin Park\"", "\"text\": \"Песня\"")
        assertTrue(map(body).artists.isEmpty())
    }

    @Test
    fun unlinkedConjunctionBetweenArtistsIsNotACredit() = runTest {
        val body =
            fixture()
                .replace(
                    "\"text\": \"Linkin Park\"",
                    """"text": "pyrokinesis"},{"text": " и "},{"text": "МУККА", "navigationEndpoint":{"browseEndpoint":{"browseId":"mukka","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}""",
                )
        assertEquals(listOf("pyrokinesis", "МУККА"), map(body).artists.map { it.name })
    }

    @Test
    fun conjunctionInsideArtistNameIsPreserved() = runTest {
        val body = fixture().replace("Linkin Park", "Король и Шут")
        assertEquals(listOf("Король и Шут"), map(body).artists.map { it.name })
    }
}
