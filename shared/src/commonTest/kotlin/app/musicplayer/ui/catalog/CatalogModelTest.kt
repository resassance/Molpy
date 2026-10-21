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
}
