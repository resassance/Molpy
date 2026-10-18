package app.musicplayer.search

import app.musicplayer.model.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class SearchFallbackTest {
    @Test fun blankQueryDoesNotCallProviders() {
        val catalog = FakeCatalog()
        assertEquals(SearchResults(emptyList(), null), runSuspend { SearchCoordinator(catalog).search("  ") }.valueOrNull())
        assertEquals(0, catalog.songCalls + catalog.videoCalls + catalog.matchCalls)
    }
    @Test fun suitableMusicResultsSkipFallbackAndPreserveCatalogNames() {
        val correct = track(title = "Numb (Official Video)")
        val catalog = FakeCatalog().apply { songs = AppResult.Success(listOf(correct)) }
        assertEquals(listOf(correct), runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks)
        assertEquals(0, catalog.videoCalls)
        assertEquals(0, catalog.matchCalls)
    }
    @Test fun genuineLongLiveAndFreeFormLiveIntentBothKeepSuitablePrimaryResults() {
        listOf("Long Live" to "Long Live", "Song live" to "Song (Live)").forEach { (query, title) ->
            val original = track(title = title)
            val catalog = FakeCatalog().apply { songs = AppResult.Success(listOf(original)) }
            assertEquals(listOf(original), runSuspend { SearchCoordinator(catalog).search(query) }.valueOrNull()!!.tracks, query)
            assertEquals(0, catalog.videoCalls, query)
        }
    }
    @Test fun unrequestedNoisyLivePrimaryFallsBack() {
        val catalog = FakeCatalog().apply { songs = AppResult.Success(listOf(track(title = "Numb - Live [Official Video]"))) }
        runSuspend { SearchCoordinator(catalog).search("Numb") }
        assertEquals(1, catalog.videoCalls)
    }
    @Test fun unknownExplicitPrimaryVersionsCannotMasqueradeAsDefaultRecording() {
        listOf("live session", "live + acoustic", "mystery live mix", "alternate take").forEach { version ->
            val catalog = FakeCatalog().apply { songs = AppResult.Success(listOf(track(version = version))) }
            val result = runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!
            assertEquals(1, catalog.videoCalls, version)
            assertTrue(result.tracks.isEmpty(), version)
        }
    }
    @Test fun blankAndKnownStudioPrimaryVersionsRemainSuitable() {
        listOf("", "studio").forEach { version ->
            val original = track(version = version)
            val catalog = FakeCatalog().apply { songs = AppResult.Success(listOf(original)) }
            assertEquals(listOf(original), runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks, version)
            assertEquals(0, catalog.videoCalls, version)
        }
    }
    @Test fun unsuitableMusicResultFallsBackOnce() {
        val catalog = FakeCatalog().apply {
            songs = AppResult.Success(listOf(track(title = "Completely Different")))
            videos = AppResult.Success(listOf(track("video", tentative = true)))
        }
        assertEquals("video", runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks.single().id.value)
        assertEquals(1, catalog.songCalls)
        assertEquals(1, catalog.videoCalls)
        assertEquals(1, catalog.matchCalls)
    }
    @Test fun primaryFailureIsRetainedAfterFallbackSuccess() {
        val primaryNetworkError = AppError(ErrorCode.NETWORK, "songs", true)
        val catalog = FakeCatalog().apply {
            songs = AppResult.Failure(primaryNetworkError)
            videos = AppResult.Success(listOf(track("video", tentative = true)))
        }
        assertEquals(primaryNetworkError, runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.primaryFailure)
        assertEquals(1, catalog.videoCalls)
    }
    @Test fun fallbackFailureIsReturnedWithoutRetryLoop() {
        val failure = AppError(ErrorCode.PROVIDER_CHANGED, "videos", false)
        val catalog = FakeCatalog().apply { videos = AppResult.Failure(failure) }
        assertEquals(AppResult.Failure(failure), runSuspend { SearchCoordinator(catalog).search("Numb") })
        assertEquals(1, catalog.videoCalls)
        assertEquals(1, catalog.songCalls)
    }
    @Test fun primaryFailureIsRetainedWhenFallbackAlsoFails() {
        val primary = AppError(ErrorCode.PROXY_AUTH, "songs", false)
        val secondary = AppError(ErrorCode.NETWORK, "videos", true)
        val catalog = FakeCatalog().apply { songs = AppResult.Failure(primary); videos = AppResult.Failure(secondary) }
        assertEquals(AppResult.Failure(primary), runSuspend { SearchCoordinator(catalog).search("Numb") })
        assertEquals(1, catalog.songCalls)
        assertEquals(1, catalog.videoCalls)
    }
    @Test fun enrichmentPreservesCanonicalId() {
        val original = track("video", title = "Linkin Park - Numb [Official Video]", artist = "Uploader", tentative = true)
        val metadata = track("music", album = "Meteora")
        val catalog = FakeCatalog().apply {
            videos = AppResult.Success(listOf(original)); matches = AppResult.Success(listOf(metadata))
        }
        val enriched = runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks.single()
        assertEquals(original.id, enriched.id)
        assertEquals(original.externalIds, enriched.externalIds)
        assertEquals(original.candidates, enriched.candidates)
        assertEquals(metadata.provenance, enriched.provenance)
        assertEquals(metadata.title, enriched.title)
        assertEquals(metadata.album, enriched.album)
        assertEquals(metadata.artists, enriched.artists)
        assertEquals("Numb", catalog.matchedTracks.single().title)
        assertEquals("Linkin Park", catalog.matchedTracks.single().artists.single().name)
    }
    @Test fun tentativeDisplayPreservesFeatAndVersionWithoutInventingAlbum() {
        val original = track("video", title = "Band - Song (feat. Guest) (Live) [Official Video] [HD]", tentative = true)
        val catalog = FakeCatalog().apply { videos = AppResult.Success(listOf(original)) }
        val result = runSuspend { SearchCoordinator(catalog).search("Song live") }.valueOrNull()!!.tracks.single()
        assertEquals("Song (feat. Guest) (Live)", result.title)
        assertEquals("Band", result.artists.single().name)
        assertNull(result.album)
        assertTrue(result.provenance.tentative)
        assertEquals(original.candidates, result.candidates)
    }
    @Test fun ambiguousOrMissingDurationDoesNotEnrich() {
        listOf(listOf(track("a"), track("b")), listOf(track("missing", duration = null))).forEach { matches ->
            val original = track("video", title = "Linkin Park - Numb", artist = "Uploader", tentative = true)
            val catalog = FakeCatalog().apply { videos = AppResult.Success(listOf(original)); this.matches = AppResult.Success(matches) }
            val result = runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks.single()
            assertTrue(result.provenance.tentative)
            assertEquals(original.id, result.id)
        }
    }
    @Test fun enrichmentFailureLeavesPartialTrackAndDoesNotRetry() {
        val original = track("video", tentative = true).copy(artists = listOf(ArtistRef("Linkin Park", SourceRef("youtube_music", "artist", "linkin-park"))))
        val catalog = FakeCatalog().apply {
            videos = AppResult.Success(listOf(original)); matches = AppResult.Failure(AppError(ErrorCode.NETWORK, "enrich", true))
        }
        assertEquals(original, runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks.single())
        assertEquals(1, catalog.matchCalls)
    }
    @Test fun uploaderIsNotExposedAsMusicArtistWhenRawTitleCannotEstablishOne() {
        val original = track("video", title = "Numb [Official Video]", artist = "Uploader", tentative = true)
        val catalog = FakeCatalog().apply { videos = AppResult.Success(listOf(original)) }
        val result = runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks.single()
        assertEquals("Numb", result.title)
        assertTrue(result.artists.isEmpty())
        assertNull(result.album)
        assertEquals(original.id, result.id)
    }
    @Test fun verifiedMusicArtistSurvivesWithoutArtistTitleDelimiter() {
        val artist = ArtistRef("Linkin Park", SourceRef("youtube_music", "artist", "linkin-park"))
        val original = track("video", title = "Numb [Official Video]", tentative = true).copy(artists = listOf(artist))
        val catalog = FakeCatalog().apply { videos = AppResult.Success(listOf(original)) }
        assertEquals(listOf(artist), runSuspend { SearchCoordinator(catalog).search("Numb") }.valueOrNull()!!.tracks.single().artists)
    }
    @Test fun cancellationPropagatesAtEveryProviderBoundary() {
        listOf("songs", "videos", "matches").forEach { stage ->
            val catalog = FakeCatalog().apply {
                videos = AppResult.Success(listOf(track("video", tentative = true)))
                when (stage) {
                    "songs" -> songAction = { throw CancellationException("cancel") }
                    "videos" -> videoAction = { throw CancellationException("cancel") }
                    "matches" -> matchAction = { throw CancellationException("cancel") }
                }
            }
            assertFailsWith<CancellationException>(stage) { runSuspend { SearchCoordinator(catalog).search("Numb") } }
        }
    }
}
