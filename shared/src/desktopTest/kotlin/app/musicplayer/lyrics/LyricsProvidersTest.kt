package app.musicplayer.lyrics

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsProvidersTest {
    private val source = SourceRef("youtube", "video", "recording-one")
    private val candidate = PlaybackCandidate(CandidateId("candidate-one"), source, "studio", 180000, MatchConfidence.HIGH)
    private val track = Track(TrackId("track-one"), "Synthetic Song", listOf(ArtistRef("Synthetic Artist", null)), AlbumRef("Synthetic Album", null),
        Artwork(ArtworkKind.PLACEHOLDER, null, null), 180000, setOf(source), MetadataProvenance(SourceRef("youtube_music", "song", source.id), 0, false), listOf(candidate))
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val plain = NormalizedLyrics("Synthetic words", emptyList(), LyricsKind.PLAIN, SourceRef("synthetic", "lyrics", "one"))
    private fun port(block: suspend () -> AppResult<LyricsCandidate?>) = object : LyricsPort {
        override suspend fun fetch(track: Track, candidate: PlaybackCandidate) = block()
    }
    private fun response(extra: String = "") = """{"id":17,"trackName":"Synthetic Song","artistName":"Synthetic Artist","albumName":"Synthetic Album","duration":180,"instrumental":false,"plainLyrics":"Synthetic words","syncedLyrics":"[00:00.00]Synthetic words"$extra}"""

    @Test fun lrclibQueriesMetadataAndReturnsBoundSynchronizedLyrics() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("lrclib.net", request.url.host)
            assertEquals("/api/get", request.url.encodedPath)
            assertEquals("Synthetic Song", request.url.parameters["track_name"])
            assertEquals("Synthetic Artist", request.url.parameters["artist_name"])
            assertEquals("Synthetic Album", request.url.parameters["album_name"])
            assertEquals("180", request.url.parameters["duration"])
            respond(response(), HttpStatusCode.OK, jsonHeaders)
        })
        try {
            val result = assertNotNull(LrcLibLyrics(http).fetch(track, candidate).valueOrNull())
            assertTrue(result.versionMatched)
            assertTrue(result.timingValid)
            assertEquals(LyricsKind.LINE_SYNCED, result.lyrics.kind)
            assertEquals(0L, result.lyrics.lines.single().startMs)
        } finally { http.close() }
    }

    @Test fun lrclibRejectsWrongTitleArtistDurationAndVersionBinding() = runTest {
        for (body in listOf(response().replace("Synthetic Song", "Other Song"), response().replace("Synthetic Artist", "Other Artist"), response().replace("180", "250"), response().replace("Synthetic Song", "Synthetic Song (Live)"))) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) })
            try { assertNull(LrcLibLyrics(http).fetch(track, candidate).valueOrNull()) } finally { http.close() }
        }
    }

    @Test fun lrclibHttpAbsenceFailureAndChangedSchemaStayDistinct() = runTest {
        for ((status, expected) in listOf(404 to null, 429 to ErrorCode.RATE_LIMIT, 503 to ErrorCode.NETWORK, 200 to ErrorCode.PROVIDER_CHANGED)) {
            val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status), jsonHeaders) })
            try {
                val result = LrcLibLyrics(http).fetch(track, candidate)
                if (expected == null) assertEquals(AppResult.Success(null), result)
                else assertEquals(expected, result.errorOrNull()?.code)
            } finally { http.close() }
        }
    }

    @Test fun lrclib404IsKnownAbsenceWithCallerExpectSuccessEnabled() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound, jsonHeaders) }) { expectSuccess = true }
        try { assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track, candidate)) }
        finally { http.close() }
    }

    @Test fun knownCandidateVersionMustAlsoMatchEvenWhenRawTitleHasNoMarker() = runTest {
        val http = HttpClient(MockEngine { respond(response(), HttpStatusCode.OK, jsonHeaders) })
        try { assertNull(LrcLibLyrics(http).fetch(track, candidate.copy(version = "live")).valueOrNull()) }
        finally { http.close() }
    }

    @Test fun allKnownCandidateVersionMarkersMustMatch() = runTest {
        val http = HttpClient(MockEngine { respond(response().replace("Synthetic Song", "Synthetic Song (Cover)"), HttpStatusCode.OK, jsonHeaders) })
        try { assertNull(LrcLibLyrics(http).fetch(track, candidate.copy(version = "live + cover")).valueOrNull()) }
        finally { http.close() }
    }

    @Test fun unsupportedCandidateVersionsCannotConfirmPlainOrSyncedRecording() = runTest {
        for (version in listOf("alternate take", "mystery mix", "unknown")) {
            for (body in listOf(response(), response().replace("\"syncedLyrics\":\"[00:00.00]Synthetic words\"", "\"syncedLyrics\":null"))) {
                val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) })
                try {
                    assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track, candidate.copy(version = version)), version)
                } finally { http.close() }
            }
        }
    }

    @Test fun blankVersionNeedsPositiveCatalogEvidenceBeforeAnyPositiveCacheEntry() = runTest {
        val insufficient = listOf(
            track.copy(provenance = track.provenance.copy(tentative = true)),
            track.copy(album = null),
            track.copy(provenance = track.provenance.copy(source = SourceRef("youtube_music", "song", "other"))),
            track.copy(durationMs = null),
        )
        for (metadata in insufficient) {
            val http = HttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond(response(), HttpStatusCode.OK, jsonHeaders) }
            }))
            try {
                val cache = SessionLyricsCache()
                val selected = candidate.copy(version = "", durationMs = metadata.durationMs)
                val lookup = LyricsLoader(listOf(LrcLibLyrics(http)), LyricsSelector(), cache, { 0 }).load(metadata, selected)
                assertFalse(lookup is LyricsLookup.Available, metadata.toString())
                assertFalse(cache.getLyrics(metadata.id, selected.id, 0) is LyricsLookup.Available, metadata.toString())
            } finally { http.close() }
        }
    }

    @Test fun catalogBlankVersionCanUseAffirmativeIdentityAndMatchingMetadata() = runTest {
        // Actual CatalogMapper candidates have version=""; strong identity is evidence rather than an invented studio label.
        val http = HttpClient(MockEngine { respond(response(), HttpStatusCode.OK, jsonHeaders) })
        try {
            val result = assertNotNull(LrcLibLyrics(http).fetch(track, candidate.copy(version = "")).valueOrNull())
            assertTrue(result.versionMatched)
            assertEquals(LyricsKind.LINE_SYNCED, result.lyrics.kind)
        } finally { http.close() }
    }

    @Test fun blankVersionCannotInferIdentityWhenProviderAlbumIsUnknown() = runTest {
        val http = HttpClient(MockEngine { respond(response().replace("\"albumName\":\"Synthetic Album\"", "\"albumName\":null"), HttpStatusCode.OK, jsonHeaders) })
        try { assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track, candidate.copy(version = ""))) }
        finally { http.close() }
    }

    @Test fun blankVersionCannotInferIdentityFromTentativeCandidate() = runTest {
        val http = HttpClient(MockEngine { respond(response(), HttpStatusCode.OK, jsonHeaders) })
        try { assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track, candidate.copy(version = "", confidence = MatchConfidence.TENTATIVE))) }
        finally { http.close() }
    }

    @Test fun differentRemasterYearsCannotConfirmTheSameRecording() = runTest {
        val body = response().replace("Synthetic Song", "Synthetic Song (Remaster 2009)")
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) })
        try {
            assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track.copy(title = "Synthetic Song (Remaster 2011)"), candidate.copy(version = "remaster 2011")))
            assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track.copy(title = "Synthetic Song (Remaster 2009)"), candidate.copy(version = "remaster 2011")))
        } finally { http.close() }
    }

    @Test fun contradictoryLiveDescriptorsCannotConfirmTheSameRecording() = runTest {
        val http = HttpClient(MockEngine { respond(response().replace("Synthetic Song", "Synthetic Song (Live at North)"), HttpStatusCode.OK, jsonHeaders) })
        try {
            assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track.copy(title = "Synthetic Song (Live at South)"), candidate.copy(version = "live")))
            assertEquals(AppResult.Success(null), LrcLibLyrics(http).fetch(track.copy(title = "Synthetic Song (Live at North)"), candidate.copy(version = "live at South")))
        } finally { http.close() }
    }

    @Test fun supportedBaseAndCorroboratedExplicitVersionsRemainAvailable() = runTest {
        val cases = listOf(
            Triple("Synthetic Song", "original", "Synthetic Song"),
            Triple("Synthetic Song (Live at North)", "live", "Synthetic Song (Live at North)"),
            Triple("Synthetic Song (Live at North)", "", "Synthetic Song (Live at North)"),
            Triple("Synthetic Song (Remaster 2011)", "remaster 2011", "Synthetic Song (Remaster 2011)"),
            Triple("Synthetic Song", "remaster 2011", "Synthetic Song (Remaster 2011)"),
        )
        for ((title, version, providerTitle) in cases) {
            val http = HttpClient(MockEngine { respond(response().replace("Synthetic Song", providerTitle), HttpStatusCode.OK, jsonHeaders) })
            try {
                val result = assertNotNull(LrcLibLyrics(http).fetch(track.copy(title = title), candidate.copy(version = version)).valueOrNull(), "$title / $version")
                assertTrue(result.versionMatched)
                assertEquals(LyricsKind.LINE_SYNCED, result.lyrics.kind)
            } finally { http.close() }
        }
    }

    @Test fun missingDurationEvidenceKeepsSuitableLyricsPlain() = runTest {
        val http = HttpClient(MockEngine { respond(response(), HttpStatusCode.OK, jsonHeaders) })
        try {
            val result = assertNotNull(LrcLibLyrics(http).fetch(track.copy(durationMs = null), candidate.copy(durationMs = null)).valueOrNull())
            assertEquals(LyricsKind.PLAIN, result.lyrics.kind)
        } finally { http.close() }
    }

    @Test fun knownSelectedDurationRequiresProviderDurationForAnySelection() = runTest {
        val http = HttpClient(MockEngine { respond(response().replace("\"duration\":180", "\"duration\":null"), HttpStatusCode.OK, jsonHeaders) })
        try { assertNull(LrcLibLyrics(http).fetch(track, candidate).valueOrNull()) }
        finally { http.close() }
    }

    @Test fun unknownCandidateVersionStillUsesRawTitleMarkers() = runTest {
        val http = HttpClient(MockEngine { respond(response(), HttpStatusCode.OK, jsonHeaders) })
        try { assertNull(LrcLibLyrics(http).fetch(track.copy(title = "Synthetic Song (Live)"), candidate.copy(version = "")).valueOrNull()) }
        finally { http.close() }
    }

    @Test fun tentativeAndMismatchedProvenanceKeepSuitableTextPlain() = runTest {
        val http = HttpClient(MockEngine { respond(response(), HttpStatusCode.OK, jsonHeaders) })
        try {
            for (t in listOf(track.copy(provenance = track.provenance.copy(tentative = true)), track.copy(provenance = track.provenance.copy(source = SourceRef("youtube_music", "song", "other"))))) {
                val result = assertNotNull(LrcLibLyrics(http).fetch(t, candidate).valueOrNull())
                assertTrue(result.versionMatched)
                assertEquals(LyricsKind.PLAIN, result.lyrics.kind)
                assertTrue(result.lyrics.lines.isEmpty())
            }
            val tentative = assertNotNull(LrcLibLyrics(http).fetch(track, candidate.copy(confidence = MatchConfidence.TENTATIVE)).valueOrNull())
            assertEquals(LyricsKind.PLAIN, tentative.lyrics.kind)
        } finally { http.close() }
    }

    @Test fun lrclibMalformedTimingFallsBackToPlainAndInstrumentalIsDistinct() = runTest {
        val http = HttpClient(MockEngine { respond(response().replace("[00:00.00]", "[-00:01]"), HttpStatusCode.OK, jsonHeaders) })
        try { assertEquals(LyricsKind.PLAIN, assertNotNull(LrcLibLyrics(http).fetch(track, candidate).valueOrNull()).lyrics.kind) } finally { http.close() }
        val instrumental = HttpClient(MockEngine(MockEngineConfig().apply {
            // Share virtual time with the provider budget; the default IO dispatcher can race runTest's clock.
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(response().replace("\"instrumental\":false", "\"instrumental\":true"), HttpStatusCode.OK, jsonHeaders) }
        }))
        try {
            val lookup = LyricsLoader(listOf(LrcLibLyrics(instrumental)), LyricsSelector(), SessionLyricsCache(), { 0 }).load(track, candidate)
            assertIs<LyricsLookup.Instrumental>(lookup, "lookup=$lookup virtualTime=${testScheduler.currentTime}")
        } finally { instrumental.close() }
    }

    @Test fun malformedSyncedOnlyResponseIsFailureRatherThanKnownAbsence() = runTest {
        val body = response().replace("[00:00.00]", "[-00:01]").replace("\"plainLyrics\":\"Synthetic words\"", "\"plainLyrics\":null")
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) })
        try { assertEquals(ErrorCode.PROVIDER_CHANGED, LrcLibLyrics(http).fetch(track, candidate).errorOrNull()?.code) }
        finally { http.close() }
    }

    @Test fun providerFailurePlusAbsenceStaysFailedAndIsNotNegativeCached() = runTest {
        var requests = 0
        val loader = LyricsLoader(listOf(port { requests++; AppResult.Failure(AppError(ErrorCode.NETWORK, "test", true)) }, port { AppResult.Success(null) }), LyricsSelector(), SessionLyricsCache(), { 0 })
        assertIs<LyricsLookup.Failed>(loader.load(track, candidate))
        assertIs<LyricsLookup.Failed>(loader.load(track, candidate))
        assertEquals(2, requests)
    }

    @Test fun noConfiguredProvidersIsFailureRatherThanKnownAbsence() = runTest {
        val result = assertIs<LyricsLookup.Failed>(LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache(), { 0 }).load(track, candidate))
        assertEquals(ErrorCode.INVALID_CONFIG, result.error.code)
    }

    @Test fun bothProviderFailuresStayFailed() = runTest {
        val failed = port { AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture", true)) }
        assertIs<LyricsLookup.Failed>(LyricsLoader(listOf(failed, failed), LyricsSelector(), SessionLyricsCache(), { 0 }).load(track, candidate))
    }

    @Test fun providerTimeoutIsBoundedAndOtherProviderStillSucceeds() = runTest {
        val loader = LyricsLoader(listOf(port { awaitCancellation() }, port { AppResult.Success(LyricsCandidate(plain, true, false)) }), LyricsSelector(), SessionLyricsCache(), { testScheduler.currentTime })
        assertIs<LyricsLookup.Available>(loader.load(track, candidate))
        assertEquals(6000L, testScheduler.currentTime)
        assertIs<LyricsLookup.Failed>(LyricsLoader(listOf(port { awaitCancellation() }), LyricsSelector(), SessionLyricsCache(), { 0 }).load(track, candidate))
    }

    @Test fun cancellationPropagatesWithoutBeingCached() = runTest {
        val loader = LyricsLoader(listOf(port { throw CancellationException("cancelled") }), LyricsSelector(), SessionLyricsCache(), { 0 })
        assertFailsWith<CancellationException> { loader.load(track, candidate) }
    }

    @Test fun loaderIsLazyAndReusesCandidateSpecificCache() = runTest {
        var calls = 0
        val loader = LyricsLoader(listOf(port { calls++; AppResult.Success(LyricsCandidate(plain, true, false)) }), LyricsSelector(), SessionLyricsCache(), { 0 })
        assertEquals(0, calls)
        assertIs<LyricsLookup.Available>(loader.load(track, candidate))
        assertIs<LyricsLookup.Available>(loader.load(track, candidate))
        assertEquals(1, calls)
        loader.load(track, candidate.copy(id = CandidateId("other")))
        assertEquals(2, calls)
    }

    @Test fun knownUnavailableExpiresAt24HoursAndWeakerCacheDoesNotOverwriteSynced() = runTest {
        val cache = SessionLyricsCache()
        cache.saveLyrics(track.id, candidate.id, LyricsLookup.Unavailable, 0)
        assertIs<LyricsLookup.Unavailable>(cache.getLyrics(track.id, candidate.id, 86_399_999))
        assertNull(cache.getLyrics(track.id, candidate.id, 86_400_000))
        val synced = plain.copy(kind = LyricsKind.LINE_SYNCED, lines = listOf(LyricLine("Synthetic words", 0, 180000)))
        cache.saveLyrics(track.id, candidate.id, LyricsLookup.Available(synced), 1)
        cache.saveLyrics(track.id, candidate.id, LyricsLookup.Available(plain), 2)
        cache.saveLyrics(track.id, candidate.id, LyricsLookup.Unavailable, 3)
        assertEquals(synced, assertIs<LyricsLookup.Available>(cache.getLyrics(track.id, candidate.id, 4)).lyrics)
    }

    @Test fun failureIsNeverCachedEvenWhenSavedDirectly() = runTest {
        val cache = SessionLyricsCache()
        cache.saveLyrics(track.id, candidate.id, LyricsLookup.Failed(AppError(ErrorCode.NETWORK, "fixture", true)), 0)
        assertNull(cache.getLyrics(track.id, candidate.id, 1))
    }

    @Test fun youtubeUsesAnonymousNextAndBrowseAndOnlyMapsTaggedDescriptionShelf() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            if (request.url.encodedPath.endsWith("next")) {
                assertEquals(source.id, body["videoId"]?.jsonPrimitive?.content)
                respond(nextFixture, HttpStatusCode.OK, jsonHeaders)
            } else {
                assertEquals("lyrics-one", body["browseId"]?.jsonPrimitive?.content)
                respond(browseFixture, HttpStatusCode.OK, jsonHeaders)
            }
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val tube = InnerTube(http, retryDelay = {})
            tube.cookie = "SAPISID=synthetic-only"
            tube.useLoginForBrowse = true
            val result = assertNotNull(YouTubeLyrics(tube).fetch(track, candidate).valueOrNull())
            assertEquals(LyricsKind.PLAIN, result.lyrics.kind)
            assertEquals("Synthetic line one\nSynthetic line two", result.lyrics.plainText)
            assertTrue(result.lyrics.lines.isEmpty())
            assertEquals(listOf("/youtubei/v1/next", "/youtubei/v1/browse"), paths)
        } finally { http.close() }
    }

    @Test fun advertisedYoutubeLyricsWithUnknownBrowseSchemaIsFailure() = runTest {
        val http = HttpClient(MockEngine { request -> respond(if (request.url.encodedPath.endsWith("next")) nextFixture else "{}", HttpStatusCode.OK, jsonHeaders) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try { assertEquals(ErrorCode.PROVIDER_CHANGED, YouTubeLyrics(InnerTube(http)).fetch(track, candidate).errorOrNull()?.code) }
        finally { http.close() }
    }

    @Test fun youtubeExplicitNullLyricsEndpointIsKnownAbsenceButUnknownNextIsFailure() = runTest {
        for ((body, absent) in listOf(nextFixture.replace("\"endpoint\":{\"browseEndpoint\":{\"browseId\":\"lyrics-one\"}}", "\"endpoint\":null") to true, "{}" to false)) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            try {
                val result = YouTubeLyrics(InnerTube(http)).fetch(track, candidate)
                if (absent) assertEquals(AppResult.Success(null), result)
                else assertEquals(ErrorCode.PROVIDER_CHANGED, result.errorOrNull()?.code)
            } finally { http.close() }
        }
    }

    private val nextFixture = """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"title":"Up next"}},{"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"lyrics-one"}}}},{"tabRenderer":{"title":"Related"}}]}}}}}"""
    private val browseFixture = """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Synthetic line one\nSynthetic line two"}]},"footer":{"runs":[{"text":"Synthetic fixture"}]}}}]}}}"""
}
