package app.musicplayer.ui

import app.musicplayer.model.*
import app.musicplayer.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryQueueFlowTest {
    @Test fun simultaneousObserverFailuresStayVisibleUntilEverySourceRecovers() = runTest {
        var favoriteAttempts = 0; var recentAttempts = 0
        val recentGate = CompletableDeferred<Unit>()
        val favoriteTrack = sampleTrack("favorite"); val recentTrack = sampleTrack("recent")
        val library = object : EngineeringLibrary() {
            override val favorites = flow<List<Track>> {
                if (++favoriteAttempts == 1) throw AppFailureException(AppError(ErrorCode.DATABASE, "favorites", true))
                emit(listOf(favoriteTrack))
            }
            override val recent = flow<List<Track>> {
                if (++recentAttempts == 1) throw AppFailureException(AppError(ErrorCode.DATABASE, "recent", true))
                recentGate.await(); emit(listOf(recentTrack))
            }
        }
        val port = EngineeringPort(); val model = engineeringModel(backgroundScope, port, library); runCurrent()
        assertEquals(ErrorCode.DATABASE, model.state.value.repositoryError?.code)
        model.retryRepository(); runCurrent()
        assertEquals(listOf(favoriteTrack), model.state.value.favorites)
        assertNotNull(model.state.value.repositoryError, "Pending failed recent observer must remain visible")
        model.retryRepository(); runCurrent()
        assertEquals(2, favoriteAttempts); assertEquals(2, recentAttempts)
        recentGate.complete(Unit); runCurrent()
        assertEquals(listOf(recentTrack), model.state.value.recent)
        assertNull(model.state.value.repositoryError); assertEquals(0, port.plays); model.close()
    }
    @Test fun successfulCommandCannotDiscardFailedObserverRecovery() = runTest {
        var attempts = 0; val track = sampleTrack()
        val library = object : EngineeringLibrary() {
            override val favorites = flow<List<Track>> {
                if (++attempts == 1) throw AppFailureException(AppError(ErrorCode.DATABASE, "favorites", true))
                emit(listOf(track))
            }
        }
        val port = EngineeringPort(); val model = engineeringModel(backgroundScope, port, library); runCurrent()
        assertNotNull(model.state.value.repositoryError)
        model.setFavorite(track, true); runCurrent()
        assertNotNull(model.state.value.repositoryError, "A successful command must not hide an unsubscribed observer")
        model.retryRepository(); runCurrent()
        assertEquals(listOf(track), model.state.value.favorites); assertNull(model.state.value.repositoryError)
        assertEquals(2, attempts); assertEquals(0, port.plays); model.close()
    }
    @Test fun endedReplayRestartsExistingOccurrenceAtZero() = runTest {
        val port = EngineeringPort(); val track = sampleTrack(); val entry = QueueEntry(QueueEntryId("ended"), track.id)
        port.queue.value = QueueSnapshot(listOf(entry), entry.id, 60000, false)
        port.snapshot.value = PlaybackSnapshot(PlayerStatus.ENDED, entry, track, 60000, 60000, false)
        val model = engineeringModel(backgroundScope, port); runCurrent()
        model.playPause(); runCurrent()
        assertEquals(listOf(entry), port.selections); assertEquals(0, port.snapshot.value.positionMs)
        assertTrue(port.commands.none { it is QueueCommand.Add }); model.close()
    }
    @Test fun repositoryFlowFailuresAreNormalizedAndCanBeRetriedWithoutPlayback() = runTest {
        for (favorites in listOf(true, false)) {
            var attempts = 0
            val failing = flow<List<Track>> {
                if (++attempts == 1) {
                    if (favorites) throw AppFailureException(AppError(ErrorCode.DATABASE, "private operation", true))
                    else throw IllegalStateException("private database details")
                }
                emit(listOf(sampleTrack()))
            }
            val library = object : EngineeringLibrary() {
                override val favorites = if (favorites) failing else flowOf(emptyList())
                override val recent = if (favorites) flowOf(emptyList()) else failing
            }
            val port = EngineeringPort(); val model = engineeringModel(backgroundScope, port, library); runCurrent()
            assertEquals(ErrorCode.DATABASE, model.state.value.repositoryError?.code)
            model.retryRepository(); runCurrent()
            assertEquals(listOf(sampleTrack()), if (favorites) model.state.value.favorites else model.state.value.recent)
            assertEquals(0, port.plays); model.close()
        }
    }
    @Test fun queueMetadataReadFailureKeepsQueueAndPlaybackUsable() = runTest {
        var reads = 0; val track = sampleTrack(); val entry = QueueEntry(QueueEntryId("queue"), track.id)
        val library = object : EngineeringLibrary() {
            override suspend fun getTrack(id: TrackId): Track? {
                if (++reads == 1) throw AppFailureException(AppError(ErrorCode.DATABASE, "lookup", true))
                return track
            }
        }
        val port = EngineeringPort(); port.queue.value = QueueSnapshot(listOf(entry), entry.id)
        port.snapshot.value = PlaybackSnapshot(PlayerStatus.PAUSED, entry, track, playWhenReady = false)
        val model = engineeringModel(backgroundScope, port, library); runCurrent()
        assertEquals(ErrorCode.DATABASE, model.state.value.repositoryError?.code)
        assertEquals(listOf(entry), model.state.value.queue.entries); assertFalse(model.state.value.playback.playWhenReady)
        model.retryRepository(); runCurrent(); assertEquals(track, model.state.value.queueTracks[track.id])
        assertEquals(0, port.plays); model.close()
    }
    @Test fun observationCancellationIsNotReportedAsDatabaseFailure() = runTest {
        val library = object : EngineeringLibrary() {
            override val favorites = flow<List<Track>> { throw CancellationException("owner canceled") }
        }
        val model = engineeringModel(backgroundScope, library = library); runCurrent()
        assertNull(model.state.value.repositoryError); assertTrue(backgroundScope.isActive); model.close()
    }
    @Test fun failedLibraryCommandRetryRepeatsLibraryActionWithoutPlaying() = runTest {
        var writes = 0
        val library = object : EngineeringLibrary() {
            override suspend fun setFavorite(id: TrackId, favorite: Boolean) {
                if (++writes == 1) throw AppFailureException(AppError(ErrorCode.STORAGE_FULL, "favorite", false))
                super.setFavorite(id, favorite)
            }
        }
        val port = EngineeringPort(); val model = engineeringModel(backgroundScope, port, library); runCurrent()
        model.setFavorite(sampleTrack(), true); runCurrent()
        assertEquals(ErrorAction.MANAGE_STORAGE, present(model.state.value.commandError!!).primaryAction)
        model.retryCommand(); runCurrent()
        assertTrue(sampleTrack().id in model.state.value.favoriteIds); assertEquals(0, port.plays)
        model.close()
    }
    @Test fun removingOccurrenceKeepsFavoriteAndCommandsUseServiceQueue() = runTest {
        val port = EngineeringPort(); val library = EngineeringLibrary()
        val model = engineeringModel(backgroundScope, port, library)
        val track = sampleTrack()
        model.setFavorite(track, true); model.addToQueue(track); model.addToQueue(track); runCurrent()
        val entries = port.queue.value.entries
        assertEquals(2, entries.size); assertNotEquals(entries[0].id, entries[1].id)
        assertTrue(port.selections.isEmpty()); assertEquals(0, port.plays)
        model.selectQueue(entries[1].id); model.next(); model.previous(); model.removeQueue(entries[0].id); runCurrent()
        assertTrue(model.state.value.favoriteIds.contains(track.id))
        assertEquals(listOf(entries[1]), model.state.value.queue.entries)
        assertTrue(port.commands.contains(QueueCommand.Select(entries[1].id)))
        assertTrue(port.commands.contains(QueueCommand.Next)); assertTrue(port.commands.contains(QueueCommand.Previous))
        model.close()
    }
    @Test fun retryReusesExistingOccurrenceAndPositionWithoutAdding() = runTest {
        val port = EngineeringPort(); val track = sampleTrack(); val entry = QueueEntry(QueueEntryId("existing"), track.id)
        port.queue.value = QueueSnapshot(listOf(entry), entry.id, 1200, false)
        port.snapshot.value = PlaybackSnapshot(PlayerStatus.FAILED, entry, track, 1200, 60000, false)
        val model = engineeringModel(backgroundScope, port); runCurrent()
        model.playPause(); runCurrent()
        assertEquals(listOf(entry), port.selections)
        assertEquals(1200, port.snapshot.value.positionMs)
        assertTrue(port.commands.none { it is QueueCommand.Add })
        model.close()
    }
    @Test fun recreatedUiKeepsRestoredPausedSnapshotWithoutPlay() = runTest {
        val port = EngineeringPort(); val track = sampleTrack()
        port.snapshot.value = PlaybackSnapshot(PlayerStatus.PAUSED, QueueEntry(QueueEntryId("restored"), track.id), track, 9000, 60000, false)
        val first = engineeringModel(backgroundScope, port); first.close()
        val restored = engineeringModel(backgroundScope, port); runCurrent()
        assertFalse(restored.state.value.playback.playWhenReady); assertEquals(9000, restored.state.value.playback.positionMs)
        assertEquals(0, port.plays); restored.close()
    }
}
