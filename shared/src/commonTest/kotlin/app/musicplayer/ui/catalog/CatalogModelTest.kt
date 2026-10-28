package app.musicplayer.ui.catalog

import app.musicplayer.catalog.*
import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogModelTest {
    private val ref = AlbumRef("Release", SourceRef("youtube", "album", "one"))

    private open class Browse : BrowseCatalog {
        override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> =
            AppResult.Success(
                AlbumCatalog(
                    ref,
                    emptyList(),
                    Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    CatalogPage(listOf(sampleTrack("A")), null),
                )
            )

        override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
            error("Unexpected browse")

        override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
            error("Unexpected page")

        override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
            error("Unexpected page")
    }

    @Test
    fun sourceLessFavoritesUseScopedGroupWithoutNetwork() = runTest {
        val a = sampleTrack("A").copy(artists = listOf(ArtistRef("Same", null)))
        val b = sampleTrack("B").copy(artists = listOf(ArtistRef("Same", null)))
        val groups = CollectionGrouping.build(listOf(a, b))
        val repo =
            MutableCollections().apply {
                changes.value =
                    AppResult.Success(
                        LibraryCollections(favorites = listOf(a, b), artists = groups.second)
                    )
            }
        val model = CatalogModel(Browse(), repo, EngineeringPort(), backgroundScope)
        model.artist(a.artists.single(), groups.second.first().key)
        model.selectSection(CatalogSection.FAVORITES)
        runCurrent()
        assertEquals(listOf(a), model.state.value.favoriteTracks)
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, model.state.value.tracks.error?.code)
        model.close()
    }

    @Test
    fun lateAlbumCannotReplaceNewRoute() = runTest {
        val gate = CompletableDeferred<Unit>()
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> {
                    if (ref.source?.id == "one") withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            Artwork(ArtworkKind.PLACEHOLDER, null, null),
                            CatalogPage(listOf(sampleTrack(ref.name)), null),
                        )
                    )
                }
            }
        val model = CatalogModel(browse, MutableCollections(), EngineeringPort(), backgroundScope)
        model.album(ref)
        runCurrent()
        model.album(AlbumRef("New", SourceRef("youtube", "album", "two")))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("New", model.state.value.title)
        assertEquals("New", model.state.value.tracks.data?.items?.single()?.title)
        model.close()
    }

    @Test
    fun pageFailureRetainsTracksAndLaterPaginationDoesNotReplacePlayingList() = runTest {
        var fail = true
        val a = sampleTrack("A")
        val b = sampleTrack("B")
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef) =
                    AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            a.artwork,
                            CatalogPage(listOf(a), CatalogCursor("page")),
                        )
                    )

                override suspend fun moreTracks(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<Track>> =
                    if (fail) AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture", true))
                    else AppResult.Success(CatalogPage(listOf(b), null))
            }
        val port = EngineeringPort()
        val model = CatalogModel(browse, MutableCollections(), port, backgroundScope)
        model.album(ref)
        runCurrent()
        model.play(0)
        runCurrent()
        model.loadMore(CatalogPart.TRACKS)
        runCurrent()
        assertEquals(listOf(a), model.state.value.tracks.data?.items)
        assertNotNull(model.state.value.tracks.error)
        fail = false
        model.loadMore(CatalogPart.TRACKS)
        runCurrent()
        assertEquals(listOf(a, b), model.state.value.tracks.data?.items)
        assertEquals(listOf(a.id), port.queue.value.entries.map { it.trackId })
        model.close()
    }

    @Test
    fun returningToCanceledInitialLoadStartsAgainInsteadOfRestoringSpinner() = runTest {
        val gate = CompletableDeferred<Unit>()
        var firstCalls = 0
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> {
                    if (ref.source?.id == "one" && ++firstCalls == 1)
                        withContext(NonCancellable) { gate.await() }
                    return super.album(ref)
                }
            }
        val model = CatalogModel(browse, MutableCollections(), EngineeringPort(), backgroundScope)
        model.album(ref)
        runCurrent()
        model.album(AlbumRef("Other", SourceRef("youtube", "album", "two")))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        model.album(ref)
        runCurrent()
        assertFalse(model.state.value.tracks.loading)
        assertEquals(1, model.state.value.tracks.data?.items?.size)
        assertEquals(2, firstCalls)
        model.close()
    }

    @Test
    fun unexpectedPlaybackFailureIsNormalizedAndKeepsLoadedCatalog() = runTest {
        val port =
            object : EngineeringPort() {
                override suspend fun playList(
                    tracks: List<Track>,
                    startIndex: Int,
                ): AppResult<Unit> = throw IllegalStateException("private failure")
            }
        val model = CatalogModel(Browse(), MutableCollections(), port, backgroundScope)
        model.album(ref)
        runCurrent()
        model.play(0)
        runCurrent()
        assertEquals(ErrorCode.UNKNOWN, model.state.value.commandError?.code)
        assertEquals(1, model.state.value.tracks.data?.items?.size)
        model.close()
    }

    @Test
    fun albumIsVisibleWhileDurationLoadsAndDoesNotStartAudio() = runTest {
        val unknown = unknownDurationTrack()
        val release = CompletableDeferred<Unit>()
        var lookups = 0
        val loader = durationMetadata { sources ->
            lookups++
            release.await()
            AppResult.Success(mapOf(sources.single() to 188_000L))
        }
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef) =
                    AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            unknown.artwork,
                            CatalogPage(listOf(unknown), null),
                        )
                    )
            }
        val player = EngineeringPort()
        val model =
            CatalogModel(browse, MutableCollections(), player, backgroundScope, metadata = loader)
        model.album(ref)
        runCurrent()
        assertFalse(model.state.value.tracks.loading)
        assertEquals(unknown.id, model.state.value.tracks.data!!.items.single().id)
        assertEquals(1, lookups)
        release.complete(Unit)
        runCurrent()
        assertEquals(188_000L, model.state.value.tracks.data!!.items.single().durationMs)
        assertEquals(0, player.plays)
        model.close()
    }

    @Test
    fun leavingAlbumCancelsItsDurationRequestAndKeepsNewAlbum() = runTest {
        val missing = unknownDurationTrack()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        var lookups = 0
        val loader = durationMetadata { sources ->
            lookups++
            try {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(mapOf(sources.single() to 188_000L))
            } finally {
                cancelled = !currentCoroutineContext().isActive
            }
        }
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef) =
                    AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            missing.artwork,
                            CatalogPage(
                                listOf(
                                    if (ref.source?.id == "one") missing else sampleTrack("new")
                                ),
                                null,
                            ),
                        )
                    )
            }
        val model =
            CatalogModel(
                browse,
                MutableCollections(),
                EngineeringPort(),
                backgroundScope,
                metadata = loader,
            )
        model.album(ref)
        runCurrent()
        assertEquals(1, lookups)
        model.album(AlbumRef("New album", SourceRef("youtube", "album", "two")))
        runCurrent()
        assertEquals("new", model.state.value.tracks.data!!.items.single().title)
        release.complete(Unit)
        runCurrent()
        assertTrue(cancelled)
        assertEquals("new", model.state.value.tracks.data!!.items.single().title)
        model.close()
    }

    @Test
    fun leavingCatalogCancelsMetadataAndReconnectDoesNotRestartHiddenPage() = runTest {
        var calls = 0
        var cancelled = false
        val loader = durationMetadata {
            calls++
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val missing = unknownDurationTrack()
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef) =
                    AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            missing.artwork,
                            CatalogPage(listOf(missing), null),
                        )
                    )
            }
        val model =
            CatalogModel(
                browse,
                MutableCollections(),
                EngineeringPort(),
                backgroundScope,
                metadata = loader,
            )
        model.album(ref)
        runCurrent()
        assertEquals(1, calls)
        model.setMetadataVisible(false)
        runCurrent()
        assertTrue(cancelled)
        model.networkInvalidated()
        model.refreshMetadata()
        runCurrent()
        assertEquals(1, calls)
        model.album(ref)
        runCurrent()
        assertEquals(2, calls)
        model.close()
    }

    @Test
    fun persistedDurationCanStillRefreshAnOpenAlbumWhileOffline() = runTest {
        val missing = unknownDurationTrack()
        val library = EngineeringLibrary()
        var calls = 0
        val loader =
            durationMetadata(library) {
                calls++
                awaitCancellation()
            }
        val browse =
            object : Browse() {
                override suspend fun album(ref: AlbumRef) =
                    AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            missing.artwork,
                            CatalogPage(listOf(missing), null),
                        )
                    )
            }
        val model =
            CatalogModel(
                browse,
                MutableCollections(),
                EngineeringPort(),
                backgroundScope,
                metadata = loader,
            )
        model.album(ref)
        runCurrent()
        assertEquals(1, calls)
        library.upsert(
            missing
                .withMissingDuration(missing.candidates.first().source, 188_000)
                .copy(provenance = missing.provenance.copy(fetchedAtMs = 100))
        )
        model.networkInvalidated()
        model.album(ref)
        runCurrent()
        assertEquals(188_000L, model.state.value.tracks.data!!.items.single().durationMs)
        assertEquals(1, calls)
        model.close()
    }
}
