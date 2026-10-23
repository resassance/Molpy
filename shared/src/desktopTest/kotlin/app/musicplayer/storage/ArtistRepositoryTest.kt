package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.listening.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun <T> AppResult<T>.artistOk(): T = assertIs<AppResult.Success<T>>(this).value

internal fun artistTrack(id: String, vararg names: String): Track =
    fixture(id).copy(artists = names.map { ArtistRef(it, SourceRef("youtube", "artist", it)) })

internal fun artistCheckpoint(
    track: Track,
    id: String = track.id.value,
    played: Long = 60000,
    revision: Long = 1,
) = ListeningCheckpoint(id, revision, QueueEntryId("entry-$id"), track, 100, 100 + played, played)

class ArtistRepositoryTest {
    @Test
    fun previewCountsSavedTracksAndRejectsChangedFavoriteScope() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val y = artistTrack("y", "B")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                val b = assertNotNull(repo.ensure(y).artistOk().artists.single().id)
                repo.ensure(artistTrack("cached", "A")).artistOk()
                l.repository.setFavorite(x.id, true)
                val preview = repo.previewMerge(a, b, null).artistOk()
                assertEquals(1, preview.trackCount)
                l.repository.setFavorite(y.id, true)
                assertEquals(ArtistWriteResult.STALE, repo.merge(preview).artistOk())
                assertEquals(2, repo.previewMerge(a, b, null).artistOk().trackCount)
            } finally {
                l.close()
            }
        }

    @Test
    fun cancellationDuringOverrideRollsBackCreatedCards() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val track = artistTrack("x", "A")
                val reader = RoomArtistRepository(l.db) { 0 }
                reader.ensure(track).artistOk()
                val before = reader.directory.first().artistOk()
                lateinit var command: Deferred<AppResult<ArtistWriteResult>>
                val cancelling =
                    RoomArtistRepository(l.db) {
                        command.cancel()
                        0
                    }
                command =
                    async(start = CoroutineStart.LAZY) {
                        cancelling.saveOverride(track, listOf(ArtistChoice.Name("Cancelled")), null)
                    }
                assertFailsWith<CancellationException> { command.await() }
                assertEquals(before, reader.directory.first().artistOk())
            } finally {
                l.close()
            }
        }

    @Test
    fun sameSizedUpdatesEmitAndFavoritesUseEffectiveIdentity() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val track = artistTrack("favorite", "A")
                val a = assertNotNull(repo.ensure(track).artistOk().artists.single().id)
                l.repository.setFavorite(track.id, true)
                val b =
                    assertNotNull(
                        repo.ensure(artistTrack("other", "B")).artistOk().artists.single().id
                    )
                val collections = RoomCollectionsRepository(l.db) { 0 }
                val snapshots = mutableListOf<ArtistDirectory>()
                val ready = CompletableDeferred<Unit>()
                val job = launch {
                    repo.directory.collect {
                        snapshots += it.artistOk()
                        ready.complete(Unit)
                    }
                }
                ready.await()
                repo.saveOverride(track, listOf(ArtistChoice.Existing(b)), null).artistOk()
                withTimeout(5000) {
                    while (snapshots.last().overrides[track.id] != listOf(b)) delay(10)
                }
                repo.saveOverride(track, listOf(ArtistChoice.Existing(a)), listOf(b)).artistOk()
                withTimeout(5000) {
                    while (snapshots.last().overrides[track.id] != listOf(a)) delay(10)
                }
                val result = collections.overview.first().artistOk()
                assertEquals(a.value, result.artists.single().key)
                assertEquals(listOf(track.id), result.favorites.map { it.id })
                assertEquals(snapshots.last().revision, result.artistRevision)
                job.cancelAndJoin()
            } finally {
                l.close()
            }
        }

    @Test
    fun preferredSourceBelongsToGroupAndNoOpDoesNotChangeRevision() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val y = artistTrack("y", "B")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                val b = assertNotNull(repo.ensure(y).artistOk().artists.single().id)
                val source = x.artists.single().source
                repo.merge(repo.previewMerge(a, b, source).artistOk()).artistOk()
                assertEquals(
                    source,
                    ArtistResolver.profile(b, repo.directory.first().artistOk()).source,
                )
                repo.saveOverride(x, listOf(ArtistChoice.Existing(b)), null).artistOk()
                val before = repo.directory.first().artistOk()
                repo.saveOverride(x, listOf(ArtistChoice.Existing(b)), listOf(b)).artistOk()
                assertEquals(before, repo.directory.first().artistOk())
            } finally {
                l.close()
            }
        }

    @Test
    fun overridesRemainLocalAndOptimisticWritesRejectStaleDrafts() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val y = artistTrack("y", "A")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                repo.ensure(y).artistOk()
                assertEquals(
                    ArtistWriteResult.APPLIED,
                    repo.saveOverride(x, listOf(ArtistChoice.Name("Edited")), null).artistOk(),
                )
                val d = repo.directory.first().artistOk()
                assertEquals("Edited", ArtistResolver.present(x, d).artists.single().name)
                assertEquals(a, ArtistResolver.present(y, d).artists.single().id)
                assertEquals("A", l.repository.getTrack(x.id)!!.artists.single().name)
                assertEquals(
                    ArtistWriteResult.STALE,
                    repo.saveOverride(x, listOf(ArtistChoice.Name("Lost")), null).artistOk(),
                )
                l.reopen()
                val reopened = RoomArtistRepository(l.db) { 0 }
                val current = reopened.directory.first().artistOk()
                assertEquals(d, current)
                assertEquals(
                    ArtistWriteResult.APPLIED,
                    reopened.resetOverride(x.id, current.overrides[x.id]).artistOk(),
                )
                assertEquals(
                    a,
                    ArtistResolver.present(x, reopened.directory.first().artistOk())
                        .artists
                        .single()
                        .id,
                )
            } finally {
                l.close()
            }
        }

    @Test
    fun mergeAndSplitAreDurableDirectedAndKeepLateExplicitOverride() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                var repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                val b =
                    assertNotNull(repo.ensure(artistTrack("y", "B")).artistOk().artists.single().id)
                val c =
                    assertNotNull(repo.ensure(artistTrack("z", "C")).artistOk().artists.single().id)
                repo.merge(repo.previewMerge(a, b, null).artistOk()).artistOk()
                repo.saveOverride(x, listOf(ArtistChoice.Existing(b)), null).artistOk()
                repo.merge(repo.previewMerge(b, c, null).artistOk()).artistOk()
                l.reopen()
                repo = RoomArtistRepository(l.db) { 0 }
                val split = repo.previewSplit(b).artistOk()
                assertEquals(setOf(a, b), split.detached.map { it.id }.toSet())
                repo.split(split).artistOk()
                val d = repo.directory.first().artistOk()
                assertEquals(b, ArtistResolver.root(a, d))
                assertEquals(c, ArtistResolver.root(c, d))
                assertEquals(listOf(b), d.overrides[x.id])
                repo.split(repo.previewSplit(a).artistOk()).artistOk()
                assertEquals(listOf(b), repo.directory.first().artistOk().overrides[x.id])
            } finally {
                l.close()
            }
        }

    @Test
    fun changedAssignmentsInvalidatePreviewButListeningTicksDoNot() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                val b =
                    assertNotNull(repo.ensure(artistTrack("y", "B")).artistOk().artists.single().id)
                val preview = repo.previewMerge(a, b, null).artistOk()
                RoomListeningRepository(l.db) { 0 }.save(artistCheckpoint(x)).artistOk()
                assertEquals(ArtistWriteResult.APPLIED, repo.merge(preview).artistOk())
                val undo = repo.previewSplit(a).artistOk()
                repo.saveOverride(x, listOf(ArtistChoice.Existing(b)), null).artistOk()
                assertEquals(ArtistWriteResult.STALE, repo.split(undo).artistOk())
                assertEquals(b, ArtistResolver.root(a, repo.directory.first().artistOk()))
            } finally {
                l.close()
            }
        }

    @Test
    fun invalidSourceAndUnknownArtistAreRejectedWithoutPartialWrites() =
        runBlocking<Unit> {
            val l = TestLibrary()
            try {
                l.open()
                val repo = RoomArtistRepository(l.db) { 0 }
                val x = artistTrack("x", "A")
                val a = assertNotNull(repo.ensure(x).artistOk().artists.single().id)
                val b =
                    assertNotNull(repo.ensure(artistTrack("y", "B")).artistOk().artists.single().id)
                val before = repo.directory.first().artistOk()
                assertIs<AppResult.Failure>(
                    repo.saveOverride(
                        x,
                        listOf(
                            ArtistChoice.Name("Partial"),
                            ArtistChoice.Existing(ArtistId("missing")),
                        ),
                        null,
                    )
                )
                assertEquals(before, repo.directory.first().artistOk())
                assertIs<AppResult.Failure>(
                    repo.previewMerge(a, b, SourceRef("youtube", "artist", "foreign"))
                )
                assertIs<AppResult.Failure>(repo.previewMerge(a, a, null))
                assertIs<AppResult.Failure>(repo.saveOverride(x, emptyList(), null))
                val hit = ArtistCatalogHit("Channel", SourceRef("youtube", "channel", "bad"), null)
                assertIs<AppResult.Failure>(
                    repo.saveOverride(x, listOf(ArtistChoice.Catalog(hit)), null)
                )
            } finally {
                l.close()
            }
        }
}
