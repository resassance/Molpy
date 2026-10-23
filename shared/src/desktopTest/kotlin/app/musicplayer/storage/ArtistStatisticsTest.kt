package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ArtistStatisticsTest {
    @Test
    fun cancelledCheckpointDoesNotCommitItsSession() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                lateinit var command: Deferred<AppResult<Unit>>
                val repo =
                    RoomListeningRepository(l.db) {
                        command.cancel()
                        0
                    }
                command =
                    async(start = CoroutineStart.LAZY) {
                        repo.save(artistCheckpoint(artistTrack("cancelled", "A")))
                    }
                assertFailsWith<CancellationException> { command.await() }
                assertNull(l.db.listeningDao().session("cancelled"))
                assertEquals(
                    0L,
                    RoomListeningRepository(l.db) { 0 }.profile.first().artistOk().totalMs,
                )
            } finally {
                l.close()
            }
        }

    @Test
    fun separateSessionsSumAndConcurrentCheckpointDoesNotLoseMerge() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val y = artistTrack("y", "B")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                val b = assertNotNull(repo.ensure(y).artistOk().artists.single().id)
                val listening = RoomListeningRepository(l.db) { 0 }
                listening.save(artistCheckpoint(x)).artistOk()
                listening.save(artistCheckpoint(y, played = 40000)).artistOk()
                val preview = repo.previewMerge(a, b, null).artistOk()
                assertEquals(100000L, preview.playedMs)
                val second = l.secondDatabase()
                try {
                    coroutineScope {
                        val merge = async(Dispatchers.IO) { repo.merge(preview).artistOk() }
                        val tick =
                            async(Dispatchers.IO) {
                                RoomListeningRepository(second) { 0 }
                                    .save(artistCheckpoint(x, played = 70000, revision = 2))
                                    .artistOk()
                            }
                        merge.await()
                        tick.await()
                    }
                    val profile = listening.profile.first().artistOk()
                    assertEquals(110000L, profile.totalMs)
                    assertEquals(110000L, profile.artists.single().playedMs)
                    assertEquals(repo.directory.first().artistOk().revision, profile.artistRevision)
                } finally {
                    second.close()
                }
            } finally {
                l.close()
            }
        }

    @Test
    fun overflowRollsBackNewSessionWithoutChangingCounters() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val track = artistTrack("huge", "A")
                val listening = RoomListeningRepository(l.db) { 0 }
                val first =
                    artistCheckpoint(track)
                        .copy(
                            startedAtMs = 0,
                            lastPlayedAtMs = Long.MAX_VALUE,
                            playedMs = Long.MAX_VALUE,
                        )
                listening.save(first).artistOk()
                assertIs<AppResult.Failure>(
                    listening.save(artistCheckpoint(track, id = "overflow", played = 1))
                )
                assertEquals(Long.MAX_VALUE, listening.profile.first().artistOk().totalMs)
                assertNull(l.db.listeningDao().session("overflow"))
            } finally {
                l.close()
            }
        }

    @Test
    fun mergeCountsOneArtistOncePerSessionAndCheckpointUsesNewRoots() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val listening = RoomListeningRepository(l.db) { 0 }
                val track = artistTrack("duet", "A", "B")
                val ids = repo.ensure(track).artistOk().artists.map { assertNotNull(it.id) }
                val checkpoint = artistCheckpoint(track)
                listening.save(checkpoint).artistOk()
                val original = l.db.listeningDao().session(checkpoint.sessionId)!!.creditsJson
                repo.merge(repo.previewMerge(ids[0], ids[1], null).artistOk()).artistOk()
                val merged = listening.profile.first().artistOk()
                assertEquals(60000L, merged.artists.single().playedMs)
                assertEquals(60000L, merged.totalMs)
                listening
                    .save(checkpoint.copy(revision = 2, playedMs = 70000, lastPlayedAtMs = 70100))
                    .artistOk()
                listening
                    .save(checkpoint.copy(revision = 2, playedMs = 70000, lastPlayedAtMs = 70100))
                    .artistOk()
                assertEquals(70000L, listening.profile.first().artistOk().artists.single().playedMs)
                assertEquals(
                    original,
                    l.db.listeningDao().session(checkpoint.sessionId)!!.creditsJson,
                )
                repo.split(repo.previewSplit(ids[0]).artistOk()).artistOk()
                assertEquals(
                    listOf(70000L, 70000L),
                    listening.profile.first().artistOk().artists.map { it.playedMs },
                )
            } finally {
                l.close()
            }
        }

    @Test
    fun correctionReallocatesOnlySelectedTrackAndResetRestoresOriginalCredits() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val listening = RoomListeningRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val y = artistTrack("y", "A")
                listening.save(artistCheckpoint(x)).artistOk()
                listening.save(artistCheckpoint(y, played = 40000)).artistOk()
                assertEquals(
                    100000L,
                    listening.profile.first().artistOk().artists.single().playedMs,
                )
                repo.saveOverride(x, listOf(ArtistChoice.Name("Correct")), null).artistOk()
                val times = listening.profile.first().artistOk()
                assertEquals(
                    mapOf("Correct" to 60000L, "A" to 40000L),
                    times.artists.associate { it.artist.name to it.playedMs },
                )
                assertEquals(100000L, times.totalMs)
                repo
                    .resetOverride(x.id, repo.directory.first().artistOk().overrides[x.id])
                    .artistOk()
                assertEquals(
                    100000L,
                    listening.profile.first().artistOk().artists.single().playedMs,
                )
            } finally {
                l.close()
            }
        }

    @Test
    fun projectionFailureRollsBackMergeAndAllowsSamePreviewRetry() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val listening = RoomListeningRepository(l.db) { 0 }
                val x = artistTrack("x", "A", "B")
                val ids = repo.ensure(x).artistOk().artists.map { assertNotNull(it.id) }
                listening.save(artistCheckpoint(x)).artistOk()
                val preview = repo.previewMerge(ids[0], ids[1], null).artistOk()
                val before = repo.directory.first().artistOk()
                l.db.useWriterConnection { c ->
                    c.usePrepared(
                        "CREATE TRIGGER fail_projection BEFORE INSERT ON artist_listening BEGIN SELECT RAISE(ABORT, 'fixture'); END"
                    ) {
                        it.step()
                    }
                }
                assertIs<AppResult.Failure>(repo.merge(preview))
                assertEquals(before, repo.directory.first().artistOk())
                l.db.useWriterConnection { c ->
                    c.usePrepared("DROP TRIGGER fail_projection") { it.step() }
                }
                assertEquals(ArtistWriteResult.APPLIED, repo.merge(preview).artistOk())
                assertEquals(60000L, listening.profile.first().artistOk().artists.single().playedMs)
            } finally {
                l.close()
            }
        }
}
