package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.search.*
import app.musicplayer.ui.*
import app.musicplayer.ui.catalog.CatalogModel
import app.musicplayer.ui.search.SearchModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistArtworkSeedWiringTest {
    private val art = Artwork(ArtworkKind.RELEASE, "https://art.test/artist.png", null)

    private fun ref(id: String) = ArtistRef(id, SourceRef("youtube", "artist", id))

    private fun hit(id: String, artwork: Artwork? = art) =
        ArtistCatalogHit(id, requireNotNull(ref(id).source), artwork)

    private fun page(vararg items: SearchItem) =
        SearchPage(
            items.firstOrNull(),
            listOf(SearchSection(SearchFilter.ARTISTS, items.drop(1))),
            null,
        )

    private val fallback =
        object : CatalogPort {
            override suspend fun searchSongs(query: String) = AppResult.Success(emptyList<Track>())

            override suspend fun searchVideos(query: String) = AppResult.Success(emptyList<Track>())

            override suspend fun findMusicMatches(track: Track) =
                AppResult.Success(emptyList<Track>())
        }

    @Test
    fun seedCallbacksOnlyReceiveCurrentArtistSourcesFromSearch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seeded = mutableListOf<Pair<SourceRef, Artwork>>()
        val primary =
            object : MusicSearchPort {
                override suspend fun search(
                    query: String,
                    filter: SearchFilter,
                ): AppResult<SearchPage> {
                    if (query == "old") withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(
                        page(
                            SearchItem.ArtistItem(ref(query), art),
                            SearchItem.ArtistItem(ref("null"), null),
                            SearchItem.ArtistItem(
                                ref("placeholder"),
                                Artwork(ArtworkKind.PLACEHOLDER, null, null),
                            ),
                            SearchItem.ArtistItem(
                                ArtistRef("invalid", SourceRef("youtube", "video", "invalid")),
                                art,
                            ),
                            SearchItem.TrackItem(
                                sampleTrack("song"),
                                SourceRef("youtube", "video", "song"),
                            ),
                        )
                    )
                }

                override suspend fun more(cursor: CatalogCursor) = error("No paging")
            }
        val model =
            SearchModel(
                MusicSearchCoordinator(primary, SearchCoordinator(fallback)),
                EngineeringPort(),
                backgroundScope,
                rememberArtistArtwork = { source, artwork -> seeded += source to artwork },
            )
        model.search("old")
        runCurrent()
        model.search("current")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(seeded.isNotEmpty())
        assertEquals(setOf(requireNotNull(ref("current").source) to art), seeded.toSet())
        model.close()
    }

    @Test
    fun seedCallbacksOnlyReceiveCurrentArtistSourcesFromBrowse() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seeded = mutableListOf<Pair<SourceRef, Artwork>>()
        val browse =
            object : BrowseCatalog {
                override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> {
                    if (ref.name == "old") withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(
                        ArtistCatalog(
                            ref,
                            if (ref.name == "null") null else art,
                            CatalogPage(emptyList(), null),
                            CatalogPage(emptyList(), null),
                            CatalogPage(emptyList(), null),
                        )
                    )
                }

                override suspend fun album(ref: AlbumRef) =
                    AppResult.Success(
                        AlbumCatalog(ref, emptyList(), art, CatalogPage(emptyList(), null))
                    )

                override suspend fun moreTracks(cursor: CatalogCursor) = error("No paging")

                override suspend fun moreAlbums(cursor: CatalogCursor) = error("No paging")
            }
        val model =
            CatalogModel(
                browse,
                MutableCollections(),
                EngineeringPort(),
                backgroundScope,
                rememberArtistArtwork = { source, artwork -> seeded += source to artwork },
            )
        model.artist(ref("old"))
        runCurrent()
        model.artist(ref("current"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        model.artist(ref("null"))
        runCurrent()
        model.artist(ArtistRef("invalid", SourceRef("youtube", "video", "invalid")))
        runCurrent()
        model.album(AlbumRef("album", SourceRef("youtube", "album", "album")))
        runCurrent()
        assertEquals(listOf(requireNotNull(ref("current").source) to art), seeded)
        model.close()
    }

    @Test
    fun seedCallbacksOnlyReceiveCurrentArtistSourcesFromEditorSearch() = runTest {
        val repo = ArtistModelRepository()
        val gate = CompletableDeferred<Unit>()
        val seeded = mutableListOf<Pair<SourceRef, Artwork>>()
        val search =
            ArtistModelSearch().apply {
                query = { query ->
                    if (query == "old") withContext(NonCancellable) { gate.await() }
                    AppResult.Success(
                        listOf(
                            hit(query),
                            hit("null", null),
                            hit("placeholder", Artwork(ArtworkKind.PLACEHOLDER, null, null)),
                        )
                    )
                }
            }
        val model =
            ArtistEditorModel(
                repo,
                search,
                backgroundScope,
                rememberArtistArtwork = { source, artwork -> seeded += source to artwork },
            )
        model.open(repo.track)
        runCurrent()
        model.setQuery("old")
        advanceTimeBy(301)
        runCurrent()
        model.setQuery("current")
        advanceTimeBy(301)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(requireNotNull(ref("current").source) to art), seeded)
        model.close()
    }

    @Test
    fun editorValidationFailureNeverSeedsUnvalidatedArtwork() = runTest {
        val repo = ArtistModelRepository()
        val seeded = mutableListOf<Pair<SourceRef, Artwork>>()
        val model =
            ArtistEditorModel(
                repo,
                ArtistModelSearch(),
                backgroundScope,
                rememberArtistArtwork = { source, artwork -> seeded += source to artwork },
            )
        model.open(repo.track)
        runCurrent()
        model.add(ArtistChoice.Catalog(hit("unvalidated")))
        model.save()
        runCurrent()
        assertTrue(seeded.isEmpty())
        assertNotNull(model.state.value.error)
        model.close()
    }

    @Test
    fun editorSeedsValidatedSourceAndRejectsLateValidationAfterDraftChange() = runTest {
        val repo = ArtistModelRepository()
        val gate = CompletableDeferred<Unit>()
        val seeded = mutableListOf<Pair<SourceRef, Artwork>>()
        var late = false
        val search =
            object : ArtistSearchPort {
                override suspend fun search(query: String) =
                    AppResult.Success(emptyList<ArtistCatalogHit>())

                override suspend fun validate(hit: ArtistCatalogHit): AppResult<ArtistCatalogHit> {
                    if (late) withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(this@ArtistArtworkSeedWiringTest.hit("validated"))
                }
            }
        val model =
            ArtistEditorModel(
                repo,
                search,
                backgroundScope,
                rememberArtistArtwork = { source, artwork -> seeded += source to artwork },
            )
        model.open(repo.track)
        runCurrent()
        model.add(ArtistChoice.Catalog(hit("unvalidated")))
        model.save()
        runCurrent()
        assertEquals(listOf(requireNotNull(ref("validated").source) to art), seeded)
        seeded.clear()
        model.open(repo.track)
        runCurrent()
        model.add(ArtistChoice.Catalog(hit("late")))
        late = true
        model.save()
        runCurrent()
        model.open(repo.other)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(seeded.isEmpty(), "Late validation must never seed the new draft")
        model.close()
    }
}
