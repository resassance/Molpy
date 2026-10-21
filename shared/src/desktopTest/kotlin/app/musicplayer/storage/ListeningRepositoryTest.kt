package app.musicplayer.storage

import androidx.room.useWriterConnection
import app.musicplayer.listening.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ListeningRepositoryTest {
    private fun checkpoint(
        id: String = "session",
        revision: Long = 1,
        played: Long = 5000,
        start: Long = 100,
        track: Track = fixture("played"),
    ) =
        ListeningCheckpoint(
            id,
            revision,
            QueueEntryId("entry-$id"),
            track,
            start,
            start + played,
            played,
        )

    @Test
    fun cumulativeCheckpointsAreIdempotentDurableAndCreditEveryCollaborator() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                var repo = RoomListeningRepository(library.db) { 0 }
                val first = checkpoint()
                repo.save(first).ok()
                repo.save(first).ok()
                assertEquals(
                    ErrorCode.INVALID_CONFIG,
                    repo.save(first.copy(playedMs = 6000)).errorOrNull()?.code,
                )
                repo.save(checkpoint(revision = 2, played = 8000)).ok()
                repo.save(first.copy(playedMs = 1000)).ok()
                assertEquals(
                    ErrorCode.INVALID_CONFIG,
                    repo.save(checkpoint(revision = 3, played = 7000)).errorOrNull()?.code,
                )
                library.reopen()
                repo = RoomListeningRepository(library.db) { 0 }
                val profile = repo.profile.first().ok()
                assertEquals(8000L, profile.totalMs)
                assertEquals(8000L, profile.tracks.single().playedMs)
                assertEquals(listOf(8000L, 8000L), profile.artists.map { it.playedMs })
                assertEquals(1, repo.history().ok().items.size)
                assertEquals(8000L, repo.history().ok().items.single().playedMs)
                library.repository.prune(Long.MAX_VALUE)
                assertNotNull(library.repository.getTrack(first.track.id))
            } finally {
                library.close()
            }
        }

    @Test
    fun artistCreditsFreezePerSessionAndNewStartsRemainSeparate() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val repo = RoomListeningRepository(library.db) { 0 }
                val original = checkpoint()
                repo.save(original).ok()
                val renamed =
                    original.track.copy(
                        artists =
                            listOf(ArtistRef("Replacement", SourceRef("other", "artist", "new")))
                    )
                repo
                    .save(
                        original.copy(
                            revision = 2,
                            playedMs = 8000,
                            lastPlayedAtMs = 8100,
                            track = renamed,
                        )
                    )
                    .ok()
                val profile = repo.profile.first().ok()
                assertEquals(
                    setOf("First", "Second"),
                    profile.artists.map { it.artist.name }.toSet(),
                )
                assertEquals(listOf(8000L, 8000L), profile.artists.map { it.playedMs })
                repo.save(checkpoint(id = "again", played = 2000, start = 9000)).ok()
                assertEquals(10000L, repo.profile.first().ok().totalMs)
                assertEquals(listOf("again", "session"), repo.history().ok().items.map { it.id })
                assertEquals(1, repo.profile.first().ok().tracks.size)
            } finally {
                library.close()
            }
        }

    @Test
    fun historyUsesStableKeysetForEqualDatesAndValidatesLimit() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val repo = RoomListeningRepository(library.db) { 0 }
                listOf("a", "b", "c").forEach { repo.save(checkpoint(id = it)).ok() }
                val first = repo.history(limit = 2).ok()
                val next = repo.history(first.next, 2).ok()
                assertEquals(listOf("c", "b", "a"), (first.items + next.items).map { it.id })
                assertNull(next.next)
                assertEquals(ErrorCode.INVALID_CONFIG, repo.history(limit = 0).errorOrNull()?.code)
                assertEquals(
                    ErrorCode.INVALID_CONFIG,
                    repo.history(limit = 101).errorOrNull()?.code,
                )
            } finally {
                library.close()
            }
        }

    @Test
    fun failedTransactionRollsBackSessionAndTotalsThenRetriesExactlyOnce() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val repo = RoomListeningRepository(library.db) { 0 }
                library.db.useWriterConnection { c ->
                    c.usePrepared(
                        "CREATE TRIGGER reject_credits BEFORE INSERT ON artist_listening BEGIN SELECT RAISE(ABORT, 'fixture'); END"
                    ) {
                        it.step()
                    }
                }
                assertEquals(ErrorCode.DATABASE, repo.save(checkpoint()).errorOrNull()?.code)
                assertEquals(0L, repo.profile.first().ok().totalMs)
                assertTrue(repo.history().ok().items.isEmpty())
                library.db.useWriterConnection { c ->
                    c.usePrepared("DROP TRIGGER reject_credits") { it.step() }
                }
                repo.save(checkpoint()).ok()
                repo.save(checkpoint()).ok()
                assertEquals(5000L, repo.profile.first().ok().totalMs)
                assertEquals(1, repo.history().ok().items.size)
            } finally {
                library.close()
            }
        }

    @Test
    fun immutableSessionIdentityRejectsReassignmentAndReadFailuresStayNormalized() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val repo = RoomListeningRepository(library.db) { 0 }
                val first = checkpoint()
                repo.save(first).ok()
                val changed = first.copy(revision = 2, track = fixture("other"))
                assertEquals(ErrorCode.INVALID_CONFIG, repo.save(changed).errorOrNull()?.code)
                assertEquals(5000L, repo.profile.first().ok().totalMs)
                assertNull(library.repository.getTrack(changed.track.id))
                library.db.close()
                assertEquals(ErrorCode.DATABASE, repo.history().errorOrNull()?.code)
                assertEquals(ErrorCode.DATABASE, repo.profile.first().errorOrNull()?.code)
            } finally {
                library.close()
            }
        }

    private fun <T> AppResult<T>.ok(): T = assertIs<AppResult.Success<T>>(this).value
}
