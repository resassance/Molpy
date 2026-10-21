package app.musicplayer.storage

import app.musicplayer.collections.*
import app.musicplayer.downloads.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CollectionsRepositoryTest {
    @Test
    fun duplicateOccurrencesReorderAndPersistWithoutDeletingOtherOwners() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val track = fixture("collected")
                library.repository.upsert(track)
                library.repository.setFavorite(track.id, true)
                val asset =
                    OfflineAsset(
                        AssetId("asset"),
                        track.id,
                        track,
                        track.candidates.single().id,
                        track.candidates.single().source,
                        Representation("f", "audio/test", "test", 10, 120000, null),
                        "saved",
                        10,
                        DownloadState.AVAILABLE,
                    )
                library.repository.saveMapping(asset)
                var collections = RoomCollectionsRepository(library.db) { 1 }
                val id = collections.create("  Mix  ").ok()
                val first = collections.append(id, track).ok()
                val second = collections.append(id, track).ok()
                assertNotEquals(first, second)
                collections.move(id, second, 0).ok()
                assertEquals(
                    listOf(second, first),
                    collections.playlist(id).first().ok()!!.items.map { it.id },
                )
                collections.remove(id, second).ok()
                library.reopen()
                collections = RoomCollectionsRepository(library.db) { 2 }
                val playlist = collections.playlist(id).first().ok()!!
                assertEquals("Mix", playlist.name)
                assertEquals(listOf(first), playlist.items.map { it.id })
                assertEquals(listOf(0), playlist.items.map { it.position })
                collections.rename(id, "New").ok()
                collections.delete(id).ok()
                collections.delete(id).ok()
                assertNull(collections.playlist(id).first().ok())
                assertEquals(listOf(track), library.repository.favorites.first())
                assertEquals(asset, library.repository.findAvailable(track.id))
            } finally {
                library.close()
            }
        }

    @Test
    fun overviewIncludesOnlyExplicitCollectionsAndPruningPreservesPlaylistMetadata() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val collections = RoomCollectionsRepository(library.db) { 0 }
                val cache = fixture("cache").copy(album = AlbumRef("Cached", null))
                val history = fixture("history").copy(album = AlbumRef("History", null))
                val saved = fixture("playlist")
                library.repository.upsert(cache)
                library.repository.upsert(history)
                library.repository.recordStarted(history.id, 1)
                assertTrue(collections.overview.first().ok().albums.isEmpty())
                val id = collections.create("Mine").ok()
                collections.append(id, saved).ok()
                library.repository.prune(Long.MAX_VALUE)
                assertNotNull(library.repository.getTrack(saved.id))
                assertNull(library.repository.getTrack(cache.id))
                val overview = collections.overview.first().ok()
                assertEquals(listOf(saved.id), overview.albums.single().tracks.map { it.id })
                assertEquals(1, overview.playlists.single().trackCount)
                collections.delete(id).ok()
                library.repository.prune(Long.MAX_VALUE)
                assertNull(library.repository.getTrack(saved.id))
                assertTrue(collections.overview.first().ok().artists.isEmpty())
            } finally {
                library.close()
            }
        }

    @Test
    fun downloadedTrackAppearsWithoutHeartAndRemovalLeavesPlaylist() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val collections = RoomCollectionsRepository(library.db) { 0 }
                val track = fixture("download")
                val asset =
                    OfflineAsset(
                        AssetId("asset"),
                        track.id,
                        track,
                        track.candidates.single().id,
                        track.candidates.single().source,
                        Representation("f", "audio/test", "test", 10, 120000, null),
                        "local",
                        10,
                        DownloadState.AVAILABLE,
                    )
                library.repository.saveMapping(asset)
                assertEquals(
                    track.id,
                    collections.overview.first().ok().albums.single().tracks.single().id,
                )
                val id = collections.create("Keep").ok()
                collections.append(id, track).ok()
                library.repository.removeMapping(asset.id)
                assertEquals(
                    track.id,
                    collections.playlist(id).first().ok()!!.items.single().track.id,
                )
                assertTrue(collections.overview.first().ok().favorites.isEmpty())
            } finally {
                library.close()
            }
        }

    @Test
    fun invalidNamesIndicesAndClosedDatabaseAreNormalizedFailures() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val collections = RoomCollectionsRepository(library.db) { 0 }
                assertEquals(ErrorCode.INVALID_CONFIG, collections.create(" ").errorOrNull()?.code)
                assertEquals(
                    ErrorCode.INVALID_CONFIG,
                    collections.create("a".repeat(121)).errorOrNull()?.code,
                )
                val id = collections.create("ok").ok()
                val item = collections.append(id, fixture("one")).ok()
                assertEquals(
                    ErrorCode.INVALID_CONFIG,
                    collections.move(id, item, 2).errorOrNull()?.code,
                )
                assertEquals(item, collections.playlist(id).first().ok()!!.items.single().id)
                library.db.close()
                assertEquals(ErrorCode.DATABASE, collections.create("later").errorOrNull()?.code)
                assertEquals(ErrorCode.DATABASE, collections.overview.first().errorOrNull()?.code)
            } finally {
                library.close()
            }
        }

    private fun <T> AppResult<T>.ok(): T = assertIs<AppResult.Success<T>>(this).value
}
