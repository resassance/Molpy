package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.test.*

class ArtistSearchMapperTest {
    @Test
    fun artistSearchDoesNotPromoteVideoOwners() {
        val result =
            assertNotNull(ArtistSearchMapper.map(artistSearchFixture("mixed")).valueOrNull())
        assertEquals(listOf(SourceRef("youtube", "artist", "UC-one")), result.map { it.source })
        assertEquals("Artist One", result.single().name)
        assertEquals("https://art.example/full", result.single().artwork?.remoteUrl)
    }

    @Test
    fun emptyAndChangedSchemaStayDistinct() {
        assertEquals(
            emptyList(),
            ArtistSearchMapper.map(artistSearchFixture("empty")).valueOrNull(),
        )
        for (body in
            listOf("{}", "[]", "{\"contents\":{\"unknownRenderer\":{}}}", "not-json")) assertEquals(
            ErrorCode.PROVIDER_CHANGED,
            ArtistSearchMapper.map(body).errorOrNull()?.code,
        )
        val incomplete =
            artistSearchFixture("mixed")
                .replace("\"browseId\": \"UC-one\"", "\"missingId\": \"UC-one\"")
        assertEquals(
            ErrorCode.PROVIDER_CHANGED,
            ArtistSearchMapper.map(incomplete).errorOrNull()?.code,
        )
    }
}

internal fun artistSearchFixture(name: String): String =
    requireNotNull(
            ArtistSearchMapperTest::class.java.getResource("/catalog/artist-search/$name.json")
        )
        .readText()
