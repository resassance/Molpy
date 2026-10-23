package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*

class ArtistStoreTest {
    private suspend fun ensure(library: TestLibrary, track: Track) =
        library.db.useWriterConnection { c ->
            c.immediateTransaction { ArtistStore(library.db).ensureRaw(track) }
        }

    @Test
    fun sourceAliasesAreDurableAndSameNamesRemainSeparate() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val track = fixture("artist-store")
            val first = ensure(library, track)
            val id = assertNotNull(first.artists.first().id)
            val other =
                track.copy(
                    id = TrackId("other"),
                    artists = listOf(track.artists.first().copy(name = "Renamed")),
                )
            assertEquals(id, ensure(library, other).artists.single().id)
            val different =
                other.copy(
                    artists =
                        listOf(ArtistRef("Renamed", SourceRef("catalog", "artist", "separate")))
                )
            assertNotEquals(id, ensure(library, different).artists.single().id)
            library.reopen()
            assertEquals(id, ensure(library, track).artists.first().id)
        } finally {
            library.close()
        }
    }

    @Test
    fun unlinkedNamesAreNotMergedAcrossTracks() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val track = fixture("unlinked").copy(artists = listOf(ArtistRef("Same", null)))
            val a = assertNotNull(ensure(library, track).artists.single().id)
            val b =
                assertNotNull(
                    ensure(library, track.copy(id = TrackId("other"))).artists.single().id
                )
            assertNotEquals(a, b)
        } finally {
            library.close()
        }
    }

    @Test
    fun overridesSurviveProviderUpdatesAndCachePruning() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val track = fixture("edited-only-search")
            library.repository.upsert(track)
            val selected =
                library.db.useWriterConnection { c ->
                    c.immediateTransaction {
                        val store = ArtistStore(library.db)
                        val id = store.create("User selection", null)
                        library.db
                            .artistDao()
                            .insertOverrides(
                                listOf(TrackArtistOverrideRecord(track.id.value, 0, id.value))
                            )
                        store.advanceRevision()
                        id
                    }
                }
            val updated = track.copy(artists = listOf(ArtistRef("New source metadata", null)))
            library.repository.upsert(updated)
            library.now = Long.MAX_VALUE
            library.repository.prune(library.now)
            assertEquals(updated, library.repository.getTrack(track.id))
            assertEquals(listOf(selected), ArtistStore(library.db).snapshot().overrides[track.id])
            library.reopen()
            assertEquals(
                "User selection",
                ArtistResolver.present(updated, ArtistStore(library.db).snapshot())
                    .artists
                    .single()
                    .name,
            )
        } finally {
            library.close()
        }
    }

    @Test
    fun twoConnectionsReuseOneSourceIdentity() = runBlocking {
        val library = TestLibrary()
        val other = library.secondDatabase()
        try {
            library.open()
            other.dao().settingsCount()
            val track =
                fixture("source-race")
                    .copy(
                        artists = listOf(ArtistRef("Same", SourceRef("catalog", "artist", "race")))
                    )
            val ids = coroutineScope {
                (0..9)
                    .map { n ->
                        async(Dispatchers.IO) {
                            val db = if (n % 2 == 0) library.db else other
                            db.useWriterConnection { c ->
                                c.immediateTransaction {
                                    ArtistStore(db).ensureRaw(track).artists.single().id
                                }
                            }
                        }
                    }
                    .awaitAll()
            }
            assertNotNull(ids.first())
            assertEquals(1, ids.distinct().size)
        } finally {
            other.close()
            library.close()
        }
    }
}
