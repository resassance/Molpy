package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.model.*
import app.musicplayer.ui.sampleTrack
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistEditorModelTest {
    @Test
    fun networkChangeClearsSpinnerWithoutDiscardingDraft() = runTest {
        val repo = ArtistModelRepository()
        val search = ArtistModelSearch()
        search.query = { awaitCancellation() }
        val model = ArtistEditorModel(repo, search, backgroundScope)
        model.open(repo.track)
        runCurrent()
        model.setQuery("Artist")
        advanceTimeBy(301)
        runCurrent()
        assertTrue(model.state.value.results.loading)
        model.networkInvalidated()
        runCurrent()
        assertFalse(model.state.value.results.loading)
        assertEquals(ErrorCode.NETWORK, model.state.value.results.error?.code)
        assertEquals(repo.track, model.state.value.track)
        assertEquals(listOf(ArtistChoice.Existing(repo.a)), model.state.value.choices)
        model.close()
    }

    @Test
    fun localEditsKeepOrderAndCanBeSavedWhileCatalogIsUnavailable() = runTest {
        val repo = ArtistModelRepository()
        val model = ArtistEditorModel(repo, ArtistModelSearch(), backgroundScope)
        model.open(repo.track)
        runCurrent()
        assertEquals(listOf(ArtistChoice.Existing(repo.a)), model.state.value.choices)
        model.add(ArtistChoice.Name(" Second "))
        model.add(ArtistChoice.Existing(repo.b))
        model.remove(1)
        assertEquals(
            listOf(ArtistChoice.Existing(repo.a), ArtistChoice.Existing(repo.b)),
            model.state.value.choices,
        )
        model.select(0, ArtistChoice.Name("Correct"))
        model.save()
        runCurrent()
        assertTrue(model.state.value.saved)
        assertFalse(model.state.value.saving)
        val directory = repo.directory.value.valueOrNull()!!
        assertEquals(
            listOf("Correct", "B"),
            ArtistResolver.present(repo.track, directory).artists.map { it.name },
        )
        assertEquals(
            listOf("A"),
            ArtistResolver.present(repo.other, directory).artists.map { it.name },
        )
        assertFailsWith<IllegalArgumentException> { ArtistChoice.Name(" ") }
        assertFailsWith<IllegalArgumentException> { ArtistChoice.Name("x".repeat(201)) }
        model.close()
    }

    @Test
    fun searchDebouncesAndLateSearchDoesNotReplaceNewDraft() = runTest {
        val repo = ArtistModelRepository()
        val search = ArtistModelSearch()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        search.query = { query ->
            started.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            AppResult.Success(
                listOf(ArtistCatalogHit(query, SourceRef("youtube", "artist", "search"), null))
            )
        }
        val model = ArtistEditorModel(repo, search, backgroundScope)
        model.open(repo.track)
        runCurrent()
        model.setQuery("a")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(0, search.calls)
        model.setQuery("old")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(0, search.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, search.calls)
        started.await()
        model.closeDraft()
        model.open(repo.other)
        runCurrent()
        model.setQuery("Новый запрос")
        finish.complete(Unit)
        runCurrent()
        assertEquals(repo.other.id, model.state.value.track?.id)
        assertEquals("Новый запрос", model.state.value.query)
        assertTrue(model.state.value.results.data.orEmpty().isEmpty())
        assertFalse(model.state.value.saving)
        model.close()
    }

    @Test
    fun failedProfileValidationPreservesDraftAndAllowsLocalName() = runTest {
        val repo = ArtistModelRepository()
        val model = ArtistEditorModel(repo, ArtistModelSearch(), backgroundScope)
        model.open(repo.track)
        runCurrent()
        val hit = ArtistCatalogHit("Chosen", SourceRef("youtube", "artist", "chosen"), null)
        model.select(0, ArtistChoice.Catalog(hit))
        model.save()
        runCurrent()
        assertFalse(model.state.value.saved)
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, model.state.value.error?.code)
        assertEquals(listOf(ArtistChoice.Catalog(hit)), model.state.value.choices)
        model.select(0, ArtistChoice.Name("Chosen"))
        model.save()
        runCurrent()
        assertTrue(model.state.value.saved)
        model.open(repo.other)
        runCurrent()
        model.remove(0)
        model.save()
        runCurrent()
        assertFalse(model.state.value.saved)
        assertEquals(ErrorCode.INVALID_CONFIG, model.state.value.error?.code)
        model.close()
    }

    @Test
    fun cancelledDraftDoesNotWriteAndDirectoryRetainsDataOnError() = runTest {
        val repo = ArtistModelRepository()
        val directory = ArtistDirectoryModel(repo, backgroundScope)
        val editor = ArtistEditorModel(repo, ArtistModelSearch(), backgroundScope)
        runCurrent()
        assertEquals(2, directory.state.value.data?.profiles?.size)
        editor.open(repo.track)
        runCurrent()
        editor.select(0, ArtistChoice.Name("Unsaved"))
        editor.closeDraft()
        runCurrent()
        assertNull(editor.state.value.track)
        assertTrue(repo.directory.value.valueOrNull()!!.overrides.isEmpty())
        repo.directory.value = AppResult.Failure(AppError(ErrorCode.DATABASE, "artists", true))
        runCurrent()
        assertEquals(2, directory.state.value.data?.profiles?.size)
        assertEquals(ErrorCode.DATABASE, directory.state.value.error?.code)
        editor.close()
        directory.close()
    }
}

internal class ArtistModelSearch : ArtistSearchPort {
    var calls = 0
    var query: suspend (String) -> AppResult<List<ArtistCatalogHit>> = {
        AppResult.Failure(AppError(ErrorCode.NETWORK, "search", true))
    }

    override suspend fun search(query: String): AppResult<List<ArtistCatalogHit>> {
        calls++
        return this.query(query)
    }

    override suspend fun validate(hit: ArtistCatalogHit): AppResult<ArtistCatalogHit> =
        AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "validate", false))
}

internal class ArtistModelRepository : ArtistRepository {
    val a = ArtistId("a")
    val b = ArtistId("b")
    val track = sampleTrack("x").copy(artists = listOf(ArtistRef("A", null)))
    val other = sampleTrack("y").copy(artists = track.artists)
    override val directory =
        MutableStateFlow<AppResult<ArtistDirectory>>(
            AppResult.Success(
                ArtistDirectory(
                    profiles =
                        mapOf(a to ArtistProfile(a, "A", null), b to ArtistProfile(b, "B", null)),
                    aliases =
                        mapOf(
                            ArtistResolver.alias(track, 0) to a,
                            ArtistResolver.alias(other, 0) to a,
                        ),
                )
            )
        )
    var mergeResult = ArtistWriteResult.APPLIED
    var mergeScope = "one"

    override suspend fun ensure(track: Track) =
        AppResult.Success(ArtistResolver.present(track, directory.value.valueOrNull()!!))

    override suspend fun saveOverride(
        track: Track,
        choices: List<ArtistChoice>,
        expected: List<ArtistId>?,
    ): AppResult<ArtistWriteResult> {
        val d = directory.value.valueOrNull()!!
        val profiles = d.profiles.toMutableMap()
        val ids = choices.mapIndexed { i, c ->
            when (c) {
                is ArtistChoice.Existing -> c.id
                is ArtistChoice.Name ->
                    ArtistId("manual-$i").also { profiles[it] = ArtistProfile(it, c.name, null) }
                is ArtistChoice.Catalog -> error("Catalog must validate")
            }
        }
        directory.value =
            AppResult.Success(
                d.copy(profiles = profiles, overrides = d.overrides + (track.id to ids))
            )
        return AppResult.Success(ArtistWriteResult.APPLIED)
    }

    override suspend fun resetOverride(
        trackId: TrackId,
        expected: List<ArtistId>?,
    ): AppResult<ArtistWriteResult> {
        val d = directory.value.valueOrNull()!!
        directory.value = AppResult.Success(d.copy(overrides = d.overrides - trackId))
        return AppResult.Success(ArtistWriteResult.APPLIED)
    }

    override suspend fun previewMerge(
        origin: ArtistId,
        target: ArtistId,
        preferredSource: SourceRef?,
    ) =
        AppResult.Success(
            ArtistMergePreview(
                origin,
                target,
                mergeScope,
                if (mergeScope == "one") 1 else 2,
                60000,
                preferredSource,
            )
        )

    override suspend fun merge(preview: ArtistMergePreview) = AppResult.Success(mergeResult)

    override suspend fun previewSplit(origin: ArtistId) =
        AppResult.Success(
            ArtistSplitPreview(
                origin,
                mergeScope,
                listOf(directory.value.valueOrNull()!!.profiles.getValue(a)),
                1,
            )
        )

    override suspend fun split(preview: ArtistSplitPreview) = AppResult.Success(mergeResult)
}
