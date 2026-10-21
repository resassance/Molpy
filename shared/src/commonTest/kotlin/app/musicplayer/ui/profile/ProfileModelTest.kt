package app.musicplayer.ui.profile

import app.musicplayer.listening.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileModelTest {
    private class Repository : ListeningRepository {
        val changes =
            MutableStateFlow<AppResult<ListeningProfile>>(AppResult.Success(ListeningProfile()))
        override val profile: Flow<AppResult<ListeningProfile>> = changes
        var response: suspend (HistoryCursor?) -> AppResult<HistoryPage> = {
            AppResult.Success(HistoryPage(emptyList(), null))
        }

        override suspend fun history(cursor: HistoryCursor?, limit: Int) = response(cursor)

        override suspend fun save(checkpoint: ListeningCheckpoint) =
            error("UI cannot write listening time")
    }

    @Test
    fun exactProfileAndCollaboratorTimesAreReadWithoutSettingHeartsOrPlaying() = runTest {
        val repo = Repository()
        val track = sampleTrack()
        repo.changes.value =
            AppResult.Success(
                ListeningProfile(
                    3660999,
                    listOf(TrackListening(track, 3660999, 10)),
                    listOf(
                        ArtistListening("a", ArtistRef("A", null), 3660999, 10),
                        ArtistListening("b", ArtistRef("B", null), 3660999, 10),
                    ),
                )
            )
        val port = EngineeringPort()
        val model = ProfileModel(repo, port, backgroundScope)
        runCurrent()
        assertEquals(3660999L, model.profile.value.data?.totalMs)
        assertEquals(
            listOf(3660999L, 3660999L),
            model.profile.value.data?.artists?.map { it.playedMs },
        )
        assertEquals(0, port.plays)
        assertNull(port.snapshot.value.track)
        model.close()
    }

    @Test
    fun historyKeepsSeparateStartsAndPriorPageOnFailureAndPlaysOnlyVisiblePreview() = runTest {
        val repo = Repository()
        val track = sampleTrack()
        val entries =
            (0..9).map { HistoryItem("s$it", track, it.toLong(), if (it == 0) null else 5000) }
        repo.response = { cursor ->
            if (cursor == null) AppResult.Success(HistoryPage(entries, HistoryCursor(0, "more")))
            else AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
        }
        val port = EngineeringPort()
        val model = ProfileModel(repo, port, backgroundScope)
        runCurrent()
        model.loadHistory()
        runCurrent()
        assertEquals(entries, model.history.value.data?.items)
        assertNotNull(model.history.value.error)
        assertNull(model.history.value.data?.items?.first()?.playedMs)
        model.playHistory(2, visibleCount = 5)
        runCurrent()
        assertEquals(5, port.queue.value.entries.size)
        assertEquals(5, port.queue.value.entries.map { it.id }.distinct().size)
        model.close()
    }

    @Test
    fun closedModelIgnoresLateHistoryResult() = runTest {
        val repo = Repository()
        val gate = CompletableDeferred<Unit>()
        repo.response = {
            withContext(NonCancellable) { gate.await() }
            AppResult.Success(
                HistoryPage(listOf(HistoryItem("late", sampleTrack(), 1, null)), null)
            )
        }
        val model = ProfileModel(repo, EngineeringPort(), backgroundScope)
        runCurrent()
        model.close()
        gate.complete(Unit)
        runCurrent()
        assertTrue(model.history.value.data?.items.orEmpty().isEmpty())
    }

    @Test
    fun unexpectedPlaybackFailureIsNormalizedWithoutDamagingHistory() = runTest {
        val repo =
            Repository().apply {
                response = {
                    AppResult.Success(
                        HistoryPage(listOf(HistoryItem("start", sampleTrack(), 1, 5000)), null)
                    )
                }
            }
        val port =
            object : EngineeringPort() {
                override suspend fun playList(
                    tracks: List<Track>,
                    startIndex: Int,
                ): AppResult<Unit> = throw IllegalStateException("private failure")
            }
        val model = ProfileModel(repo, port, backgroundScope)
        runCurrent()
        model.playHistory(0)
        runCurrent()
        assertEquals(ErrorCode.UNKNOWN, model.commandError.value?.code)
        assertEquals(1, model.history.value.data?.items?.size)
        assertFalse(model.commandError.value.toString().contains("private failure"))
        model.close()
    }
}
