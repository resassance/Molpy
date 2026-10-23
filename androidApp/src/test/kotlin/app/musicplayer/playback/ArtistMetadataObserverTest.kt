package app.musicplayer.playback

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ArtistMetadataObserverTest {
    private val source = SourceRef("youtube", "video", "track")
    private val track =
        Track(
            TrackId("track"),
            "Song",
            listOf(ArtistRef("Original", null)),
            null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null),
            60000,
            setOf(source),
            MetadataProvenance(source, 0, false),
            emptyList(),
        )
    private val a = ArtistId("a")
    private val b = ArtistId("b")

    private fun directory() =
        ArtistDirectory(
            profiles =
                mapOf(
                    a to ArtistProfile(a, "Original", null),
                    b to ArtistProfile(b, "Correct", null),
                ),
            aliases = mapOf(ArtistResolver.alias(track, 0) to a),
        )

    private fun snapshot() =
        PlaybackSnapshot(
            PlayerStatus.PLAYING,
            QueueEntry(QueueEntryId("entry"), track.id),
            track,
            5000,
            60000,
            true,
            loadId = 1,
            candidateId = CandidateId("candidate"),
            bufferedPositionMs = 20000,
        )

    private data class Update(
        val trackId: TrackId,
        val entryId: QueueEntryId,
        val loadId: Long,
        val names: List<String>,
    )

    @Test
    fun metadataChangesDoNotAlterPlaybackAndProgressDoesNotRepeatUpdates() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = MutableStateFlow(snapshot())
        val directory = MutableStateFlow<AppResult<ArtistDirectory>>(AppResult.Success(directory()))
        val updates = mutableListOf<Update>()
        val observer =
            ArtistMetadataObserver(
                snapshot,
                directory,
                { t, e, l, n ->
                    updates += Update(t, e, l, n)
                    true
                },
                owner,
            )
        try {
            assertEquals(listOf("Original"), updates.lastOrNull()?.names)
            snapshot.value = snapshot.value.copy(positionMs = 7000, bufferedPositionMs = 25000)
            val before = snapshot.value
            val count = updates.size
            val corrected = directory().copy(revision = 2, overrides = mapOf(track.id to listOf(b)))
            directory.value = AppResult.Success(corrected)
            assertEquals(count + 1, updates.size)
            assertEquals(
                Update(track.id, QueueEntryId("entry"), 1, listOf("Correct")),
                updates.last(),
            )
            assertEquals(before, snapshot.value)
            snapshot.value = before.copy(positionMs = 8000)
            directory.value = AppResult.Success(corrected.copy(revision = 3))
            assertEquals(count + 1, updates.size)
        } finally {
            observer.close()
            owner.cancel()
        }
    }

    @Test
    fun newEntryAndLoadReceiveCurrentNamesButLateOldTrackEditDoesNot() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = MutableStateFlow(snapshot())
        val dir = MutableStateFlow<AppResult<ArtistDirectory>>(AppResult.Success(directory()))
        val updates = mutableListOf<Update>()
        val observer =
            ArtistMetadataObserver(
                snapshot,
                dir,
                { t, e, l, n ->
                    updates += Update(t, e, l, n)
                    true
                },
                owner,
            )
        try {
            snapshot.value =
                snapshot.value.copy(
                    current = QueueEntry(QueueEntryId("new-entry"), track.id),
                    loadId = 2,
                )
            assertEquals(2L, updates.lastOrNull()?.loadId)
            assertEquals(QueueEntryId("new-entry"), updates.lastOrNull()?.entryId)
            val other =
                track.copy(id = TrackId("other"), artists = listOf(ArtistRef("Another", null)))
            snapshot.value =
                snapshot.value.copy(
                    current = QueueEntry(QueueEntryId("other-entry"), other.id),
                    track = other,
                    loadId = 3,
                )
            val count = updates.size
            dir.value =
                AppResult.Success(
                    directory().copy(revision = 4, overrides = mapOf(track.id to listOf(b)))
                )
            assertEquals(count, updates.size)
            assertEquals(listOf("Another"), updates.last().names)
        } finally {
            observer.close()
            owner.cancel()
        }
    }

    @Test
    fun directoryFailureRetainsLastNamesAndClosingStopsAllUpdates() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = MutableStateFlow(snapshot())
        val dir =
            MutableStateFlow<AppResult<ArtistDirectory>>(
                AppResult.Success(directory().copy(overrides = mapOf(track.id to listOf(b))))
            )
        val updates = mutableListOf<Update>()
        val observer =
            ArtistMetadataObserver(
                snapshot,
                dir,
                { t, e, l, n ->
                    updates += Update(t, e, l, n)
                    true
                },
                owner,
            )
        try {
            assertEquals(listOf("Correct"), updates.lastOrNull()?.names)
            val count = updates.size
            dir.value = AppResult.Failure(AppError(ErrorCode.DATABASE, "directory", true))
            snapshot.value = snapshot.value.copy(positionMs = 6000)
            assertEquals(count, updates.size)
            observer.close()
            dir.value = AppResult.Success(directory())
            snapshot.value = snapshot.value.copy(loadId = 4)
            assertEquals(count, updates.size)
        } finally {
            observer.close()
            owner.cancel()
        }
    }

    @Test
    fun incompleteOrStoppedIdentityDoesNotUpdateMediaItem() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = MutableStateFlow(PlaybackSnapshot())
        val dir = MutableStateFlow<AppResult<ArtistDirectory>>(AppResult.Success(directory()))
        val updates = mutableListOf<Update>()
        val observer =
            ArtistMetadataObserver(
                snapshot,
                dir,
                { t, e, l, n ->
                    updates += Update(t, e, l, n)
                    true
                },
                owner,
            )
        try {
            snapshot.value = snapshot().copy(loadId = null)
            snapshot.value =
                snapshot().copy(current = QueueEntry(QueueEntryId("wrong"), TrackId("wrong")))
            assertTrue(updates.isEmpty())
            snapshot.value = snapshot()
            assertEquals(1, updates.size)
            snapshot.value = PlaybackSnapshot()
            dir.value = AppResult.Success(directory().copy(revision = 2))
            assertEquals(1, updates.size)
            snapshot.value = snapshot()
            assertEquals(2, updates.size)
        } finally {
            observer.close()
            owner.cancel()
        }
    }
}
