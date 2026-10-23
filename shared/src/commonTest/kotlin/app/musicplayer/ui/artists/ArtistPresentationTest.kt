package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.catalog.*
import app.musicplayer.collections.*
import app.musicplayer.listening.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import app.musicplayer.ui.catalog.*
import app.musicplayer.ui.components.LoadState
import app.musicplayer.ui.library.LibraryModel
import app.musicplayer.ui.navigation.*
import app.musicplayer.ui.profile.ProfileModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistPresentationTest {
    private val a = ArtistId("a")
    private val b = ArtistId("b")
    private val x = sampleTrack("x").copy(artists = listOf(ArtistRef("Original", null)))

    private fun directory(revision: Long) =
        ArtistDirectory(
            revision,
            profiles =
                mapOf(a to ArtistProfile(a, "Correct", null), b to ArtistProfile(b, "Other", null)),
            aliases = mapOf(ArtistResolver.alias(x, 0) to a),
        )

    @Test
    fun mergedOpenCardWaitsForConsistentGroupsAndKeepsSelectedSection() = runTest {
        val before = directory(2)
        val dir = MutableStateFlow(LoadState(before))
        val repo =
            MutableCollections().apply {
                changes.value =
                    AppResult.Success(
                        LibraryCollections(
                            favorites = listOf(x),
                            artists =
                                listOf(SavedArtist(a.value, ArtistRef("Correct", null), listOf(x))),
                            artistRevision = 2,
                        )
                    )
            }
        val model =
            CatalogModel(ArtistPresentationBrowse(), repo, EngineeringPort(), backgroundScope, dir)
        runCurrent()
        model.localArtist(a)
        runCurrent()
        model.selectSection(CatalogSection.FAVORITES)
        dir.value =
            LoadState(before.copy(revision = 3, merges = mapOf(a to ArtistMerge(a, b, null, 3))))
        runCurrent()
        assertEquals(a, model.state.value.artistId)
        repo.changes.value =
            AppResult.Success(
                LibraryCollections(
                    favorites = listOf(x),
                    artists = listOf(SavedArtist(b.value, ArtistRef("Other", null), listOf(x))),
                    artistRevision = 3,
                )
            )
        runCurrent()
        assertEquals(b, model.state.value.artistId)
        assertEquals("Other", model.state.value.title)
        assertEquals(CatalogSection.FAVORITES, model.state.value.section)
        assertEquals(listOf(x), model.state.value.favoriteTracks)
        model.close()
    }

    @Test
    fun networkInvalidationDoesNotTurnLocalCardIntoSourceError() = runTest {
        val dir = MutableStateFlow(LoadState(directory(2)))
        val repo =
            MutableCollections().apply {
                changes.value =
                    AppResult.Success(
                        LibraryCollections(
                            artists =
                                listOf(SavedArtist(a.value, ArtistRef("Correct", null), listOf(x))),
                            artistRevision = 2,
                        )
                    )
            }
        val model =
            CatalogModel(ArtistPresentationBrowse(), repo, EngineeringPort(), backgroundScope, dir)
        runCurrent()
        model.localArtist(a)
        runCurrent()
        model.networkInvalidated()
        runCurrent()
        assertEquals(listOf(x), model.state.value.localTracks)
        assertNull(model.state.value.tracks.error)
        model.close()
    }

    @Test
    fun pendingArtistRevisionDoesNotShowMixedGroups() = runTest {
        val repo = MutableCollections()
        fun collections(rev: Long, key: String) =
            LibraryCollections(
                artists = listOf(SavedArtist(key, ArtistRef(key, null), listOf(x))),
                artistRevision = rev,
            )
        repo.changes.value = AppResult.Success(collections(7, "old"))
        val dir = MutableStateFlow(LoadState(directory(7)))
        val library = LibraryModel(repo, EngineeringPort(), backgroundScope, dir)
        val listening =
            object : ListeningRepository {
                override val profile =
                    MutableStateFlow<AppResult<ListeningProfile>>(
                        AppResult.Success(
                            ListeningProfile(
                                artists =
                                    listOf(
                                        ArtistListening("old", ArtistRef("old", null), 60000, 0)
                                    ),
                                artistRevision = 7,
                            )
                        )
                    )

                override suspend fun save(checkpoint: ListeningCheckpoint) =
                    error("Unexpected write")

                override suspend fun history(cursor: HistoryCursor?, limit: Int) =
                    AppResult.Success(HistoryPage(emptyList(), null))
            }
        val profile = ProfileModel(listening, EngineeringPort(), backgroundScope, dir)
        runCurrent()
        assertEquals(7, library.state.value.data?.artistRevision?.toInt())
        dir.value = LoadState(directory(8))
        runCurrent()
        assertEquals("old", library.state.value.data?.artists?.single()?.key)
        assertEquals("old", profile.profile.value.data?.artists?.single()?.key)
        repo.changes.value = AppResult.Success(collections(9, "too-new"))
        runCurrent()
        assertEquals("old", library.state.value.data?.artists?.single()?.key)
        repo.changes.value = AppResult.Success(collections(8, "merged"))
        listening.profile.value =
            AppResult.Success(
                ListeningProfile(
                    artists =
                        listOf(ArtistListening("merged", ArtistRef("merged", null), 60000, 0)),
                    artistRevision = 8,
                )
            )
        runCurrent()
        assertEquals("merged", library.state.value.data?.artists?.single()?.key)
        assertEquals(60000, profile.profile.value.data?.artists?.single()?.playedMs?.toInt())
        assertEquals("merged", profile.profile.value.data?.artists?.single()?.key)
        library.close()
        profile.close()
    }

    @Test
    fun localProfileShowsSavedAndFavoriteTracksWithoutNetworkAndCanPlayRawTrack() = runTest {
        val dir = MutableStateFlow(LoadState(directory(3)))
        val repo =
            MutableCollections().apply {
                changes.value =
                    AppResult.Success(
                        LibraryCollections(
                            favorites = listOf(x),
                            artists =
                                listOf(SavedArtist(a.value, ArtistRef("Correct", null), listOf(x))),
                            artistRevision = 3,
                        )
                    )
            }
        val port = EngineeringPort()
        val model = CatalogModel(ArtistPresentationBrowse(), repo, port, backgroundScope, dir)
        runCurrent()
        model.localArtist(a)
        runCurrent()
        assertEquals(a, model.state.value.artistId)
        assertEquals("Correct", model.state.value.title)
        assertEquals(listOf(x), model.state.value.localTracks)
        assertEquals(listOf(x), model.state.value.favoriteTracks)
        assertNull(model.state.value.tracks.error)
        model.play(0)
        runCurrent()
        assertEquals(x, port.snapshot.value.track)
        model.close()
    }

    @Test
    fun failedRemoteBrowseKeepsLocalMusicAndCanonicalFavoriteMembership() = runTest {
        val source = SourceRef("youtube", "artist", "b")
        val base = directory(4)
        val d =
            base.copy(
                profiles = base.profiles + (b to ArtistProfile(b, "Canonical", source)),
                merges = mapOf(a to ArtistMerge(a, b, source, 4)),
            )
        val dir = MutableStateFlow(LoadState(d))
        val repo =
            MutableCollections().apply {
                changes.value =
                    AppResult.Success(
                        LibraryCollections(
                            favorites = listOf(x),
                            artists =
                                listOf(
                                    SavedArtist(b.value, ArtistRef("Canonical", source), listOf(x))
                                ),
                            artistRevision = 4,
                        )
                    )
            }
        val model =
            CatalogModel(ArtistPresentationBrowse(), repo, EngineeringPort(), backgroundScope, dir)
        runCurrent()
        model.localArtist(a)
        runCurrent()
        assertEquals(b, model.state.value.artistId)
        assertEquals(listOf(x), model.state.value.localTracks)
        assertEquals(listOf(x), model.state.value.favoriteTracks)
        assertEquals(ErrorCode.NETWORK, model.state.value.tracks.error?.code)
        model.close()
    }

    @Test
    fun artistNavigationKeepsLocalIdentityAndReadsPreviousFormat() {
        val nav = AppNavigation()
        val ref = ArtistRef("Name", null)
        nav.push(AppRoute.Artist(ref, artistId = a))
        assertEquals(
            a,
            (AppNavigation.restore(nav.save()).state.value.current as AppRoute.Artist).artistId,
        )
        val old = nav.save().toMutableList()
        old[1] = "[[\"root\",\"LIBRARY\"],[\"artist\",\"Name\",\"\",\"\",\"\",\"old-key\"]]"
        assertEquals(
            AppRoute.Artist(ref, "old-key"),
            AppNavigation.restore(old).state.value.current,
        )
    }
}

private class ArtistPresentationBrowse : BrowseCatalog {
    override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
        AppResult.Failure(AppError(ErrorCode.NETWORK, "browse", true))

    override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> = error("Unexpected album")

    override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
        error("Unexpected page")

    override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
        error("Unexpected page")
}
