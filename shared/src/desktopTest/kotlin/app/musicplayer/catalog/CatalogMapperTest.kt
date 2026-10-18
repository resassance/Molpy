package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicResponsiveListItemRenderer
import com.metrolist.innertubex.models.response.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Fixtures are synthetic public metadata; mutations target relationships, identity and schema detection. */
class CatalogMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val identities = SessionIdentityStore()
    private val mapper = CatalogMapper(identities, nowMs = { 123L })

    @Test fun mapsMusicRelationships() = runTest {
        val song = songs("song").single()
        assertEquals("Numb", song.title)
        assertEquals("Linkin Park", song.artists.single().name)
        assertEquals(SourceRef("youtube", "artist", "artist-one"), song.artists.single().source)
        assertEquals("Meteora", song.album?.name)
        assertEquals(SourceRef("youtube", "album", "album-one"), song.album?.source)
        assertEquals(187_000L, song.durationMs)
        assertEquals(ArtworkKind.RELEASE, song.artwork.kind)
        assertEquals("https://example.test/video-shaped.jpg", song.artwork.remoteUrl)
        assertEquals(SourceRef("youtube_music", "song", "song-one"), song.provenance.source)
        assertFalse(song.provenance.tentative)
        assertEquals(123L, song.provenance.fetchedAtMs)
        assertEquals(song.externalIds.single(), song.candidates.single().source)
    }

    @Test fun doesNotInventAlbum() = runTest {
        val partial = songs("missing-fields").single()
        assertNull(partial.album)
        assertTrue(partial.artists.isEmpty())
        assertNull(partial.durationMs)
        assertEquals(ArtworkKind.PLACEHOLDER, partial.artwork.kind)
    }

    @Test fun reusesSourceIdentity() = runTest {
        val first = songs("song").single()
        val second = songs("song").single()
        val web = assertNotNull(mapper.mapVideos(fixture("video")).valueOrNull()).single()
        assertEquals(first.id, second.id)
        assertEquals(first.id, web.id)
        val other = songs("missing-fields").single()
        assertNotEquals(first.id, other.id)
    }

    @Test fun concurrentUpsertKeepsOneId() = runTest {
        val source = SourceRef("youtube", "video", "same-source")
        val ids = (1..50).map { async(kotlinx.coroutines.Dispatchers.Default) { identities.getOrCreate(source) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertNotEquals(ids.first(), identities.getOrCreate(source.copy(provider = "another")))
        assertNotEquals(ids.first(), identities.getOrCreate(source.copy(kind = "album")))
    }

    @Test fun schemaFailureIsNotEmptySearch() = runTest {
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapSongs(fixture("changed-schema")).errorOrNull()?.code)
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapSongs("{}").errorOrNull()?.code)
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapSongs("not json").errorOrNull()?.code)
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapVideos("{}").errorOrNull()?.code)
    }

    @Test fun explicitEmptyShelfIsSuccess() = runTest {
        val result = mapper.mapSongs("""{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[]}}]}}}""")
        assertEquals(emptyList(), assertNotNull(result.valueOrNull()))
        assertEquals(emptyList(), assertNotNull(mapper.mapVideos("""{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}}}}}""").valueOrNull()))
    }

    @Test fun messageOnlyResponseIsNotAssumedToBeEmptySearch() = runTest {
        val music = """{"contents":{"sectionListRenderer":{"contents":[{"messageRenderer":{"text":{"simpleText":"Provider error"}}}]}}}"""
        val video = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"messageRenderer":{"text":{"simpleText":"Provider error"}}}]}}]}}}}}"""
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapSongs(music).errorOrNull()?.code)
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapVideos(video).errorOrNull()?.code)
    }

    @Test fun albumResultIsNotAPlayableSong() = runTest {
        assertEquals(emptyList(), songs("album"))
    }

    @Test fun webVideoRemainsTentativeWithoutAlbumOrMusicArtistIdentity() = runTest {
        val video = assertNotNull(mapper.mapVideos(fixture("video")).valueOrNull()).single()
        assertEquals("Linkin Park - Numb (Official Video)", video.title)
        assertNull(video.album)
        assertEquals("Uploader", video.artists.single().name)
        assertNull(video.artists.single().source)
        assertTrue(video.provenance.tentative)
        assertEquals(ArtworkKind.VIDEO, video.artwork.kind)
        assertEquals(187_000L, video.durationMs)
    }

    @Test fun unknownBrowseTypeDoesNotBecomeAlbumOrArtist() = runTest {
        val body = fixture("song").replace("MUSIC_PAGE_TYPE_ALBUM", "UNKNOWN").replace("MUSIC_PAGE_TYPE_ARTIST", "CHANNEL")
        val song = assertNotNull(mapper.mapSongs(body).valueOrNull()).single()
        assertNull(song.album)
        assertTrue(song.artists.isEmpty())
    }

    @Test fun timestampShapedRelationshipNamesDoNotInventDuration() = runTest {
        val song = assertNotNull(mapper.mapSongs(songWithTimestampRelationships(duration = null)).valueOrNull()).single()
        assertEquals("2:34", song.artists.single().name)
        assertEquals("3:07", song.album?.name)
        assertNull(song.durationMs)
        assertNull(song.candidates.single().durationMs)
    }

    @Test fun timestampShapedRelationshipNamesDoNotOverwriteDuration() = runTest {
        val song = assertNotNull(mapper.mapSongs(songWithTimestampRelationships(duration = "4:20")).valueOrNull()).single()
        assertEquals("2:34", song.artists.single().name)
        assertEquals("3:07", song.album?.name)
        assertEquals(260_000L, song.durationMs)
        assertEquals(260_000L, song.candidates.single().durationMs)
    }

    @Test fun musicVideoArtworkUsesTypeMetadata() = runTest {
        val body = fixture("song").replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")
        assertEquals(ArtworkKind.VIDEO, assertNotNull(mapper.mapSongs(body).valueOrNull()).single().artwork.kind)
    }

    @Test fun brokenPlayableItemDoesNotDisappearAsEmptySearch() = runTest {
        val body = fixture("song").replace("\"videoId\": \"song-one\"", "\"videoId\": \"\"")
        assertEquals(ErrorCode.PROVIDER_CHANGED, mapper.mapSongs(body).errorOrNull()?.code)
    }

    @Test fun fixturesConsumeTaggedProviderModels() {
        val response = json.decodeFromString<SearchResponse>(fixture("song"))
        val tab = assertNotNull(response.contents?.tabbedSearchResultsRenderer)["tabs"]!!.jsonArray.single().jsonObject
        val row = tab["tabRenderer"]!!.jsonObject["content"]!!.jsonObject["sectionListRenderer"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject["musicShelfRenderer"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject["musicResponsiveListItemRenderer"]!!
        val actual = json.decodeFromJsonElement<MusicResponsiveListItemRenderer>(row)
        assertEquals("song-one", actual.playlistItemData?.videoId)
        assertEquals("Numb", actual.flexColumns.first().musicResponsiveListItemFlexColumnRenderer.text?.runs?.single()?.text)
    }

    @Test fun cancellationFromIdentityStorePropagates() = runTest {
        val cancelling = CatalogMapper(object : IdentityStore {
            override suspend fun getOrCreate(source: SourceRef): TrackId = throw kotlinx.coroutines.CancellationException("cancelled")
        }, nowMs = { 1L })
        assertFailsWith<kotlinx.coroutines.CancellationException> { cancelling.mapSongs(fixture("song")) }
    }

    @Test fun identityFailureKeepsItsCategory() = runTest {
        val failing = CatalogMapper(object : IdentityStore {
            override suspend fun getOrCreate(source: SourceRef): TrackId = throw AppFailureException(AppError(ErrorCode.DATABASE, "identity", false))
        }, nowMs = { 1L })
        assertEquals(ErrorCode.DATABASE, failing.mapSongs(fixture("song")).errorOrNull()?.code)
    }

    private suspend fun songs(name: String) = assertNotNull(mapper.mapSongs(fixture(name)).valueOrNull())

    /** Place real duration before the linked credits so a last-parseable-text bug cannot pass. */
    private fun songWithTimestampRelationships(duration: String?): String {
        fun rewrite(value: JsonElement): JsonElement = when {
            value is JsonArray && value.any { (it as? JsonObject)?.get("text") == JsonPrimitive("Linkin Park") } ->
                buildJsonArray {
                    duration?.let { add(buildJsonObject { put("text", it) }) }
                    value.filter { (it as? JsonObject)?.get("navigationEndpoint") != null }.forEach { run ->
                        val name = if (run.jsonObject["text"] == JsonPrimitive("Linkin Park")) "2:34" else "3:07"
                        add(JsonObject(run.jsonObject + ("text" to JsonPrimitive(name))))
                    }
                }
            value is JsonArray -> JsonArray(value.map(::rewrite))
            value is JsonObject -> JsonObject(value.mapValues { rewrite(it.value) })
            else -> value
        }
        return rewrite(json.parseToJsonElement(fixture("song"))).toString()
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/catalog/$name.json")).readText()
}
