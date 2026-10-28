package app.musicplayer.catalog

import app.musicplayer.model.*
import app.musicplayer.storage.TestLibrary
import app.musicplayer.storage.fixture
import kotlin.test.*
import kotlinx.coroutines.*

class TrackMetadataLoaderTest {
    private class Catalog(
        private val fetch: suspend (List<SourceRef>) -> AppResult<Map<SourceRef, Long>>
    ) : CatalogPort {
        override suspend fun durations(sources: List<SourceRef>) = fetch(sources)

        override suspend fun searchSongs(query: String): AppResult<List<Track>> = error("No search")

        override suspend fun searchVideos(query: String): AppResult<List<Track>> =
            error("No audio search")

        override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> =
            error("No matching")
    }

    @Test
    fun oldLibraryTrackGetsDurationWithoutPlayingAndRetainsItAfterReopen() = runBlocking {
        val db = TestLibrary()
        try {
            db.open()
            val old =
                fixture("old").copy(durationMs = null).let {
                    it.copy(candidates = it.candidates.map { c -> c.copy(durationMs = null) })
                }
            db.repository.upsert(old)
            db.repository.setFavorite(old.id, true)
            var calls = 0
            val loader =
                TrackMetadataLoader(
                    db.repository,
                    Catalog { sources ->
                        calls++
                        assertEquals(listOf(old.candidates.single().source), sources)
                        AppResult.Success(mapOf(sources.single() to 188_000L))
                    },
                )
            val updates = mutableListOf<List<Track>>()
            loader.load(listOf(old, old), onUpdated = updates::add)
            assertEquals(188_000L, updates.last().single().durationMs)
            db.reopen()
            assertEquals(188_000L, db.repository.getTrack(old.id)?.durationMs)
            assertEquals(1, calls)
            TrackMetadataLoader(
                    db.repository,
                    Catalog { error("Cached duration needs no network") },
                )
                .load(listOf(old)) {
                    assertEquals(188_000L, it.single().durationMs)
                }
        } finally {
            db.close()
        }
    }

    @Test
    fun firstSeenAlbumTrackIsPublishedBeforeSlowDurationLookup() = runBlocking {
        val db = TestLibrary()
        try {
            db.open()
            val old =
                fixture("album-seen").copy(durationMs = null).let {
                    it.copy(candidates = it.candidates.map { c -> c.copy(durationMs = null) })
                }
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val updates = mutableListOf<List<Track>>()
            val loader =
                TrackMetadataLoader(
                    db.repository,
                    Catalog { sources ->
                        entered.complete(Unit)
                        release.await()
                        AppResult.Success(mapOf(sources.single() to 200_000L))
                    },
                )
            val published = CompletableDeferred<Unit>()
            val task = async {
                loader.load(listOf(old)) {
                    updates += it
                    published.complete(Unit)
                }
            }
            withTimeout(5000) { published.await() }
            assertEquals(old.id, updates.first().single().id)
            assertNotNull(db.repository.getTrack(old.id))
            withTimeout(5000) { entered.await() }
            release.complete(Unit)
            task.await()
            assertEquals(200_000L, updates.last().single().durationMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun alternateCandidateDurationDoesNotReplaceCanonicalRecordingDuration() = runBlocking {
        val db = TestLibrary()
        try {
            db.open()
            val base = fixture("alternate")
            val alternate =
                base.candidates
                    .first()
                    .copy(
                        id = CandidateId("other"),
                        source = base.candidates.first().source.copy(id = "other"),
                        durationMs = null,
                    )
            val track = base.copy(candidates = base.candidates + alternate)
            db.repository.upsert(track)
            val updates = mutableListOf<List<Track>>()
            val loader =
                TrackMetadataLoader(
                    db.repository,
                    Catalog { sources ->
                        assertEquals(listOf(alternate.source), sources)
                        AppResult.Success(mapOf(alternate.source to 188_000L))
                    },
                )
            loader.load(listOf(track), onUpdated = updates::add)
            assertEquals(188_000L, updates.last().single().candidates.last().durationMs)
            assertEquals(base.durationMs, updates.last().single().durationMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun cacheOnlyRefreshKeepsKnownValuesWithoutRequestingMissingDurations() = runBlocking {
        val db = TestLibrary()
        try {
            db.open()
            val known = fixture("known-offline")
            db.repository.upsert(known)
            val missing =
                fixture("missing-offline").copy(durationMs = null).let {
                    it.copy(candidates = it.candidates.map { c -> c.copy(durationMs = null) })
                }
            var calls = 0
            val loader =
                TrackMetadataLoader(
                    db.repository,
                    Catalog {
                        calls++
                        AppResult.Success(emptyMap())
                    },
                )
            val updates = mutableListOf<List<Track>>()
            loader.load(listOf(known, missing), allowRemote = false, onUpdated = updates::add)
            assertEquals(0, calls)
            assertEquals(known.durationMs, updates.single().first().durationMs)
            assertNull(updates.single().last().durationMs)
        } finally {
            db.close()
        }
    }
}
