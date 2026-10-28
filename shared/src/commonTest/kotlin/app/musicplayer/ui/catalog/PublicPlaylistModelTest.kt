package app.musicplayer.ui.catalog

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.queue.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PublicPlaylistModelTest {
    @Test
    fun shuffleSendsAllKnownOccurrencesAndCursorInOneSourceRequest() = runTest {
        val rows = listOf(row(0, null, true), row(1), row(2), row(3, b))
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, rows, next))
            }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.playShuffled()
        model.open(ref)
        runCurrent()
        model.playShuffled()
        runCurrent()
        val request = player.requests.single()
        assertTrue(request.shuffle)
        assertEquals(rows, request.rows)
        assertEquals(next, request.next)
        assertEquals(ref, request.ref)
        assertTrue(player.commands.isEmpty())
        model.close()
    }

    @Test
    fun shuffleDoesNothingWhenEveryKnownOccurrenceIsUnavailable() = runTest {
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, listOf(row(0, null, true))))
            }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.playShuffled()
        runCurrent()
        assertTrue(player.requests.isEmpty())
        model.close()
    }

    @Test
    fun reopeningChangedPlaylistReadsFreshHeadAndDoesNotReplacePlayingQueue() = runTest {
        val refreshes = mutableListOf<Boolean>()
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> {
                    refreshes += refresh
                    return AppResult.Success(catalog(ref, listOf(row(0, if (refresh) b else a))))
                }
            }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.refresh(ref)
        runCurrent()
        assertEquals(listOf(false, true), refreshes)
        assertEquals(b, model.state.value.tracks.data!!.items.single().track)
        assertTrue(player.requests.isEmpty())
        assertEquals(0, player.stops)
    }

    private val ref = PublicPlaylistRef("Playlist", SourceRef("youtube", "playlist", "PLone"))
    private val next = CatalogCursor("next")
    private val failure = AppError(ErrorCode.NETWORK, "fixture", true)
    private val a = sampleTrack("A")
    private val b = sampleTrack("B")

    private fun row(ordinal: Int, track: Track? = a, unavailable: Boolean = false) =
        PlaylistOccurrence(
            PlaylistRowIdentity(ordinal, "item-$ordinal", track?.provenance?.source),
            track,
            unavailable,
        )

    private fun catalog(
        ref: PublicPlaylistRef = this.ref,
        rows: List<PlaylistOccurrence> = listOf(row(0)),
        next: CatalogCursor? = null,
    ) =
        PublicPlaylistCatalog(
            PublicPlaylistCard(ref, a.artwork, "Curator", null),
            CatalogPage(rows, next),
        )

    private open class Browse : BrowseCatalog {
        override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> =
            error("Unexpected album")

        override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
            error("Unexpected artist")

        override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
            error("Unexpected tracks")

        override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
            error("Unexpected albums")
    }

    private open class Player : EngineeringPort() {
        val requests = mutableListOf<PlaylistPlaybackRequest>()
        var stops = 0

        override suspend fun playPublicPlaylist(request: PlaylistPlaybackRequest): AppResult<Unit> {
            requests += request
            return AppResult.Success(Unit)
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun secondRepeatedOccurrenceIsSelectedAndUnavailableCannotPlay() = runTest {
        val rows = listOf(row(0), row(1, null, true), row(2))
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, rows, next))
            }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.play(1)
        model.play(-1)
        model.play(9)
        runCurrent()
        assertTrue(player.requests.isEmpty())
        model.play(2)
        runCurrent()
        val request = player.requests.single()
        assertEquals(2, request.selectedOrdinal)
        assertEquals(listOf(0, 1, 2), request.rows.map { it.identity.ordinal })
        assertEquals(listOf(a, null, a), request.rows.map { it.track })
        assertEquals(next, request.next)
        assertEquals(ref, request.ref)
        model.close()
    }

    @Test
    fun unavailableFlagPreventsPlayingRowsWithDisplayMetadata() = runTest {
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, listOf(row(0, a, true))))
            }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.play(0)
        runCurrent()
        assertTrue(model.state.value.tracks.data?.items?.single()?.unavailable == true)
        assertTrue(player.requests.isEmpty())
        model.close()
    }

    @Test
    fun loadedPagesSurviveNextPageFailureAndRetryContinues() = runTest {
        var fail = true
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, next = next))

                override suspend fun morePlaylist(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<PlaylistOccurrence>> {
                    assertEquals(next, cursor)
                    return if (fail) AppResult.Failure(failure)
                    else AppResult.Success(CatalogPage(listOf(row(1, b)), null))
                }
            }
        val model = PublicPlaylistModel(browse, Player(), backgroundScope)
        model.open(ref)
        runCurrent()
        model.more()
        runCurrent()
        assertEquals(listOf(a), model.state.value.tracks.data?.items?.map { it.track })
        assertEquals(failure, model.state.value.moreError)
        assertNull(model.state.value.tracks.error)
        assertFalse(model.state.value.moreLoading)
        fail = false
        model.retry()
        runCurrent()
        assertEquals(listOf(a, b), model.state.value.tracks.data?.items?.map { it.track })
        assertNull(model.state.value.tracks.data?.next)
        assertNull(model.state.value.moreError)
        model.close()
    }

    @Test
    fun initialFailureRetriesWithoutContinuationState() = runTest {
        var fail = true
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> =
                    if (fail) AppResult.Failure(failure) else AppResult.Success(catalog(ref))
            }
        val model = PublicPlaylistModel(browse, Player(), backgroundScope)
        model.open(ref)
        runCurrent()
        assertEquals(failure, model.state.value.tracks.error)
        assertNull(model.state.value.moreError)
        assertFalse(model.state.value.tracks.loading)
        fail = false
        model.retry()
        runCurrent()
        assertEquals("Curator", model.state.value.card?.owner)
        assertEquals(listOf(a), model.state.value.tracks.data?.items?.map { it.track })
        assertNull(model.state.value.tracks.error)
        model.close()
    }

    @Test
    fun viewingAnotherPlaylistDoesNotReplacePlayingQueueOrStopPlayback() = runTest {
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref))
            }
        val player = Player()
        player.playList(listOf(b), 0)
        val queue = player.queue.value
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.open(ref.copy(source = ref.source.copy(id = "PLtwo")))
        runCurrent()
        model.close()
        assertEquals(queue, player.queue.value)
        assertEquals(b, player.snapshot.value.track)
        assertTrue(player.requests.isEmpty())
        assertEquals(0, player.stops)
    }

    @Test
    fun returningToPlaylistRetainsLoadedPagesAndDoesNotFetchAgain() = runTest {
        var heads = 0
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> {
                    heads++
                    return AppResult.Success(catalog(ref, next = next))
                }

                override suspend fun morePlaylist(cursor: CatalogCursor) =
                    AppResult.Success(CatalogPage(listOf(row(1, b)), null))
            }
        val model = PublicPlaylistModel(browse, Player(), backgroundScope)
        model.open(ref)
        runCurrent()
        model.more()
        runCurrent()
        model.open(ref.copy(source = ref.source.copy(id = "PLtwo")))
        runCurrent()
        model.open(ref)
        runCurrent()
        assertEquals(2, heads)
        assertEquals(listOf(a, b), model.state.value.tracks.data?.items?.map { it.track })
        assertFalse(model.state.value.moreLoading)
        model.close()
    }

    @Test
    fun lateHeadAndPageCannotReplaceAnotherPlaylist() = runTest {
        val headGate = CompletableDeferred<Unit>()
        val pageGate = CompletableDeferred<Unit>()
        val other = ref.copy(title = "Other", source = ref.source.copy(id = "PLtwo"))
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> {
                    if (ref.source.id == "PLslow") withContext(NonCancellable) { headGate.await() }
                    return AppResult.Success(catalog(ref, next = next))
                }

                override suspend fun morePlaylist(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<PlaylistOccurrence>> {
                    withContext(NonCancellable) { pageGate.await() }
                    return AppResult.Success(CatalogPage(listOf(row(1, b)), null))
                }
            }
        val model = PublicPlaylistModel(browse, Player(), backgroundScope)
        model.open(ref.copy(source = ref.source.copy(id = "PLslow")))
        runCurrent()
        model.open(ref)
        runCurrent()
        model.more()
        runCurrent()
        model.more()
        model.open(other)
        runCurrent()
        headGate.complete(Unit)
        pageGate.complete(Unit)
        runCurrent()
        assertEquals(other, model.state.value.ref)
        assertEquals(listOf(a), model.state.value.tracks.data?.items?.map { it.track })
        assertFalse(model.state.value.moreLoading)
        model.close()
    }

    @Test
    fun unexpectedFailuresAreNormalizedAndPlaybackErrorCannotReplaceNewCommand() = runTest {
        var pageCalls = 0
        val gate = CompletableDeferred<Unit>()
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, next = next))

                override suspend fun morePlaylist(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<PlaylistOccurrence>> {
                    pageCalls++
                    throw IllegalStateException("private detail")
                }
            }
        var plays = 0
        val player =
            object : Player() {
                override suspend fun playPublicPlaylist(
                    request: PlaylistPlaybackRequest
                ): AppResult<Unit> =
                    when (++plays) {
                        1 -> throw IllegalStateException("private detail")
                        2 -> {
                            withContext(NonCancellable) { gate.await() }
                            AppResult.Failure(failure)
                        }
                        else -> AppResult.Success(Unit)
                    }
            }
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.more()
        model.more()
        runCurrent()
        assertEquals(1, pageCalls)
        assertEquals(ErrorCode.UNKNOWN, model.state.value.moreError?.code)
        assertFalse(model.state.value.moreLoading)
        model.play(0)
        runCurrent()
        assertEquals(ErrorCode.UNKNOWN, model.state.value.commandError?.code)
        model.play(0)
        runCurrent()
        model.play(0)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(model.state.value.commandError)
        assertEquals(listOf(a), model.state.value.tracks.data?.items?.map { it.track })
        model.close()
    }

    @Test
    fun networkChangeDiscardsOldCursorsAndReopensThroughActivatedCatalog() = runTest {
        var heads = 0
        val gate = CompletableDeferred<Unit>()
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> {
                    heads++
                    if (heads == 2) assertTrue(refresh)
                    return AppResult.Success(
                        catalog(ref, rows = listOf(row(0, if (heads == 1) a else b)), next = next)
                    )
                }

                override suspend fun morePlaylist(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<PlaylistOccurrence>> {
                    withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(CatalogPage(listOf(row(1)), null))
                }
            }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope)
        model.open(ref)
        runCurrent()
        model.more()
        runCurrent()
        model.networkInvalidated()
        model.play(0)
        runCurrent()
        assertTrue(player.requests.isEmpty())
        assertNull(model.state.value.tracks.data)
        assertEquals(ErrorCode.NETWORK, model.state.value.tracks.error?.code)
        model.networkActivated()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, heads)
        assertEquals(listOf(b), model.state.value.tracks.data?.items?.map { it.track })
        assertFalse(model.state.value.moreLoading)
        model.close()
    }

    @Test
    fun returningToCanceledHeadDoesNotRestoreEndlessLoading() = runTest {
        val gate = CompletableDeferred<Unit>()
        var firstCalls = 0
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> {
                    if (ref.source.id == "PLone" && ++firstCalls == 1)
                        withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(catalog(ref))
                }
            }
        val model = PublicPlaylistModel(browse, Player(), backgroundScope)
        model.open(ref)
        runCurrent()
        model.open(ref.copy(source = ref.source.copy(id = "PLtwo")))
        runCurrent()
        model.open(ref)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(a), model.state.value.tracks.data?.items?.map { it.track })
        assertFalse(model.state.value.tracks.loading)
        assertEquals(2, firstCalls)
        model.close()
    }

    @Test
    fun durationUpdatePreservesRepeatedAndUnavailablePlaylistOccurrences() = runTest {
        val unknown = unknownDurationTrack()
        val rows = listOf(row(0, unknown), row(1, unknown), row(2, null, true))
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, rows))
            }
        val loader = durationMetadata { sources ->
            AppResult.Success(mapOf(sources.single() to 188_000L))
        }
        val player = Player()
        val model = PublicPlaylistModel(browse, player, backgroundScope, metadata = loader)
        model.open(ref)
        runCurrent()
        val actual = model.state.value.tracks.data!!.items
        assertEquals(rows.map { it.identity }, actual.map { it.identity })
        assertEquals(listOf(188_000L, 188_000L, null), actual.map { it.track?.durationMs })
        assertTrue(actual.last().unavailable)
        assertTrue(player.requests.isEmpty())
        model.close()
    }

    @Test
    fun hiddenPlaylistDoesNotRestartMetadataAfterReconnection() = runTest {
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
        val browse =
            object : Browse() {
                override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
                    AppResult.Success(catalog(ref, listOf(row(0, unknownDurationTrack()))))
            }
        val model = PublicPlaylistModel(browse, Player(), backgroundScope, metadata = loader)
        model.open(ref)
        runCurrent()
        assertEquals(1, calls)
        model.setMetadataVisible(false)
        runCurrent()
        assertTrue(cancelled)
        model.networkInvalidated()
        model.networkActivated()
        runCurrent()
        assertEquals(1, calls)
        model.open(ref)
        runCurrent()
        assertEquals(2, calls)
        model.close()
    }
}
