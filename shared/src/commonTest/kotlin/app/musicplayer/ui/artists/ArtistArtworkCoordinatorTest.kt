package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistArtworkCoordinatorTest {
    private val a = SourceRef("youtube", "artist", "UC-a")
    private val b = SourceRef("youtube", "artist", "UC-b")
    private val c = SourceRef("youtube", "artist", "UC-c")
    private val photo = Artwork(ArtworkKind.RELEASE, "https://img.example/a", null)

    private class Repository : ArtistArtworkRepository {
        val entries = mutableMapOf<SourceRef, ArtistArtworkEntry>()
        val saved = mutableListOf<ArtistArtworkEntry>()
        val changes = MutableStateFlow(0)
        var failSave = false

        override fun observe(source: SourceRef): Flow<ArtistArtworkEntry?> = changes.map {
            entries[source]
        }

        override suspend fun get(source: SourceRef): AppResult<ArtistArtworkEntry?> =
            AppResult.Success(entries[source])

        override suspend fun save(entry: ArtistArtworkEntry): AppResult<Unit> {
            if (failSave) return AppResult.Failure(AppError(ErrorCode.DATABASE, "save", false))
            saved += entry
            if ((entries[entry.source]?.checkedAtMs ?: -1) <= entry.checkedAtMs)
                entries[entry.source] = entry
            changes.value++
            return AppResult.Success(Unit)
        }
    }

    private class Source : ArtistArtworkSource {
        val calls = mutableListOf<SourceRef>()
        val cancelled = mutableListOf<SourceRef>()
        var active = 0
        var peak = 0
        var action: suspend (SourceRef) -> AppResult<Artwork?> = { awaitCancellation() }

        override suspend fun fetch(source: SourceRef): AppResult<Artwork?> {
            calls += source
            active++
            peak = maxOf(peak, active)
            try {
                return action(source)
            } catch (e: CancellationException) {
                cancelled += source
                throw e
            } finally {
                active--
            }
        }
    }

    private fun TestScope.coordinator(repo: Repository, source: Source) =
        ArtistArtworkCoordinator(repo, source, backgroundScope, { testScheduler.currentTime })

    // A model owner may be born after invalidation and before route activation.
    // Suspension must gate provider work, while cached local portraits remain usable.
    @Test
    fun unreadyNewOwnerReadsCachedLocalPhotoWithoutFetchingOrWritingRetry() = runTest {
        val local = photo.copy(localKey = "/saved/artist.png")
        val repo =
            Repository().apply {
                entries[a] = ArtistArtworkEntry(a, local, 0, 0, ArtistArtworkOutcome.AVAILABLE)
            }
        val source = Source().apply { action = { AppResult.Success(photo) } }
        val model = coordinator(repo, source).apply { networkInvalidated() }
        model.updateInterest("new-owner", linkedSetOf(a, b))
        runCurrent()
        assertEquals(local, model.state.value[a]?.artwork)
        assertFalse(model.state.value.values.any { it.loading })
        assertTrue(source.calls.isEmpty())
        assertTrue(repo.saved.isEmpty())
        assertNull(repo.entries[b])
        model.networkActivated()
        runCurrent()
        assertEquals(setOf(a, b), source.calls.toSet())
        assertEquals(photo, model.state.value[b]?.artwork)
        assertTrue(repo.saved.all { it.outcome == ArtistArtworkOutcome.AVAILABLE })
        model.close()
    }

    @Test
    fun unreadyUncachedOwnerDoesNotCreateCooldownBeforeActivation() = runTest {
        val repo = Repository()
        val source =
            Source().apply {
                action = { AppResult.Failure(AppError(ErrorCode.NETWORK, "provider", true)) }
            }
        val model = coordinator(repo, source).apply { networkInvalidated() }
        model.updateInterest("new-owner", setOf(a))
        runCurrent()
        assertNull(repo.entries[a])
        assertTrue(source.calls.isEmpty())
        model.networkActivated()
        runCurrent()
        assertEquals(ArtistArtworkOutcome.RETRY, repo.entries[a]?.outcome)
        assertEquals(300_000L, repo.entries[a]?.refreshAfterMs)
        model.networkInvalidated()
        model.networkActivated()
        runCurrent()
        assertEquals(1, source.calls.size, "Actual provider failure retains its cooldown")
        model.close()
    }

    @Test
    fun visibleSourcesDeduplicateAndNeverExceedTwoRequests() = runTest {
        val repo = Repository()
        val source = Source()
        val model = coordinator(repo, source)
        model.updateInterest("one", linkedSetOf(a, b, c))
        model.updateInterest("two", setOf(a, b))
        runCurrent()
        assertEquals(2, source.active)
        assertEquals(2, source.calls.distinct().size)
        model.releaseInterest("two")
        model.updateInterest("one", setOf(c))
        runCurrent()
        assertEquals(listOf(c), source.calls.filter { it == c })
        assertTrue(source.peak <= 2)
        model.close()
    }

    @Test
    fun leavingViewportReleasesInterest() = runTest {
        val source = Source()
        val model = coordinator(Repository(), source)
        model.updateInterest("view", linkedSetOf(a, b, c))
        runCurrent()
        model.releaseInterest("view")
        runCurrent()
        assertEquals(0, source.active)
        assertFalse(c in source.calls)
        assertFalse(model.state.value.values.any { it.loading })
        model.close()
    }

    @Test
    fun twoOwnersShareSourceUntilBothLeave() = runTest {
        val source = Source()
        val model = coordinator(Repository(), source)
        model.updateInterest("first", setOf(a))
        model.updateInterest("second", setOf(a))
        runCurrent()
        model.releaseInterest("first")
        runCurrent()
        assertEquals(listOf(a), source.calls)
        assertEquals(1, source.active)
        model.releaseInterest("second")
        runCurrent()
        assertEquals(listOf(a), source.cancelled)
        model.close()
    }

    @Test
    fun unrelatedNamesStaySeparate() = runTest {
        val source =
            Source().apply {
                action = {
                    AppResult.Success(
                        if (it == a) photo else photo.copy(remoteUrl = "https://img.example/b")
                    )
                }
            }
        val model = coordinator(Repository(), source)
        model.updateInterest("same-displayed-name", setOf(a, b))
        runCurrent()
        assertEquals(photo, model.state.value[a]?.artwork)
        assertEquals("https://img.example/b", model.state.value[b]?.artwork?.remoteUrl)
        model.close()
    }

    @Test
    fun seededPhotoAvoidsUnneededFetch() = runTest {
        val repo = Repository()
        val source = Source()
        val model = coordinator(repo, source)
        model.remember(a, photo)
        runCurrent()
        model.updateInterest("view", setOf(a))
        runCurrent()
        assertTrue(source.calls.isEmpty())
        assertEquals(photo, model.state.value[a]?.artwork)
        assertEquals(ArtistArtworkOutcome.AVAILABLE, repo.entries[a]?.outcome)
        model.close()
    }

    @Test
    fun nullSearchThumbnailDoesNotNegativeCache() = runTest {
        val repo = Repository()
        val source = Source()
        val model = coordinator(repo, source)
        model.remember(a, Artwork(ArtworkKind.PLACEHOLDER, null, null))
        model.remember(b, photo.copy(remoteUrl = " "))
        model.remember(c.copy(kind = "album"), photo)
        runCurrent()
        assertTrue(repo.saved.isEmpty())
        model.updateInterest("view", setOf(a))
        runCurrent()
        assertEquals(listOf(a), source.calls)
        model.close()
    }

    @Test
    fun successAbsentRetryHaveDifferentExpiry() = runTest {
        val day = 86_400_000L
        for ((result, outcome, ttl) in
            listOf(
                Triple(AppResult.Success(photo), ArtistArtworkOutcome.AVAILABLE, 30 * day),
                Triple(AppResult.Success(null), ArtistArtworkOutcome.ABSENT, day),
                Triple(
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "fetch", true)),
                    ArtistArtworkOutcome.RETRY,
                    300_000L,
                ),
            )) {
            val repo = Repository()
            val source = Source().apply { action = { result } }
            val model = coordinator(repo, source)
            val start = testScheduler.currentTime
            model.updateInterest("view", setOf(a))
            runCurrent()
            val entry = assertNotNull(repo.entries[a])
            assertEquals(outcome, entry.outcome)
            assertEquals(start + ttl, entry.refreshAfterMs)
            model.releaseInterest("view")
            runCurrent()
            advanceTimeBy(ttl - 1)
            model.updateInterest("view", setOf(a))
            runCurrent()
            assertEquals(1, source.calls.size)
            model.releaseInterest("view")
            runCurrent()
            advanceTimeBy(1)
            model.updateInterest("view", setOf(a))
            runCurrent()
            assertEquals(2, source.calls.size)
            model.close()
        }
    }

    @Test
    fun retryKeepsPreviousPhoto() = runTest {
        val repo =
            Repository().apply {
                entries[a] = ArtistArtworkEntry(a, photo, 0, 0, ArtistArtworkOutcome.AVAILABLE)
            }
        val source =
            Source().apply {
                action = { AppResult.Failure(AppError(ErrorCode.NETWORK, "fetch", true)) }
            }
        val model = coordinator(repo, source)
        model.updateInterest("view", setOf(a))
        runCurrent()
        assertEquals(photo, model.state.value[a]?.artwork)
        assertEquals(ArtistArtworkOutcome.RETRY, repo.entries[a]?.outcome)
        assertEquals(photo, repo.entries[a]?.artwork)
        model.close()
    }

    @Test
    fun networkChangeRejectsLateOldRoute() = runTest {
        val release = CompletableDeferred<Unit>()
        var first = true
        val repo = Repository()
        val source =
            Source().apply {
                action = {
                    if (first) {
                        first = false
                        withContext(NonCancellable) { release.await() }
                        AppResult.Success(photo)
                    } else AppResult.Success(photo.copy(remoteUrl = "https://img.example/new"))
                }
            }
        val model = coordinator(repo, source)
        model.updateInterest("view", setOf(a))
        runCurrent()
        model.networkInvalidated()
        runCurrent()
        model.networkActivated()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals("https://img.example/new", model.state.value[a]?.artwork?.remoteUrl)
        assertFalse(repo.saved.any { it.artwork == photo })
        model.close()
    }

    @Test
    fun closeCancelsJobsWithoutPlaybackSideEffects() = runTest {
        val source = Source()
        val repo = Repository()
        val model = coordinator(repo, source)
        model.updateInterest("view", setOf(a, b))
        runCurrent()
        model.close()
        runCurrent()
        assertEquals(0, source.active)
        assertTrue(repo.saved.isEmpty())
        model.updateInterest("after-close", setOf(c))
        model.networkActivated()
        runCurrent()
        assertFalse(c in source.calls)
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun rememberWinsAgainstLateFetchAndSaveFailureStillShowsPhoto() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Repository().apply { failSave = true }
        val source =
            Source().apply {
                action = {
                    withContext(NonCancellable) { gate.await() }
                    AppResult.Success(null)
                }
            }
        val model = coordinator(repo, source)
        model.updateInterest("view", setOf(a))
        runCurrent()
        advanceTimeBy(1)
        model.remember(a, photo)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(photo, model.state.value[a]?.artwork)
        model.close()
    }

    @Test
    fun moreThan256VisibleSourcesUseBoundedDeterministicAdmission() = runTest {
        val all = (0 until 300).map { SourceRef("youtube", "artist", "UC-bounded-$it") }
        val repo = Repository()
        val source = Source().apply { action = { AppResult.Success(photo) } }
        val model = coordinator(repo, source)
        model.updateInterest("viewport", all.toCollection(linkedSetOf()))
        runCurrent()
        assertEquals(all.take(256).toSet(), source.calls.toSet())
        assertEquals(256, source.calls.size)
        assertEquals(all.take(256).toSet(), model.state.value.keys)
        assertTrue(model.state.value.values.all { it.artwork == photo && !it.loading })
        assertTrue(source.peak <= 2)

        model.updateInterest("viewport", all.drop(1).toCollection(linkedSetOf()))
        runCurrent()
        assertEquals(all[256], source.calls.last())
        assertEquals(257, source.calls.size)
        assertEquals(all.drop(1).take(256).toSet(), model.state.value.keys)
        assertTrue(model.state.value.size <= 256)
        model.close()
    }

    @Test
    fun sequentialCancelledSourcesDoNotRetainStateOnlyKeys() = runTest {
        val repo = Repository()
        val source = Source()
        val model = coordinator(repo, source)
        repeat(300) { index ->
            val visible = SourceRef("youtube", "artist", "UC-cancelled-$index")
            model.updateInterest("viewport", setOf(visible))
            runCurrent()
            assertEquals(setOf(visible), model.state.value.keys)
            assertTrue(model.state.value.getValue(visible).loading)
            model.releaseInterest("viewport")
            runCurrent()
            assertTrue(
                model.state.value.isEmpty(),
                "Cancelled viewport source $index remains retained",
            )
            assertEquals(0, source.active)
        }
        assertEquals(300, source.cancelled.size)
        assertTrue(repo.saved.isEmpty())
        model.close()
    }
}
