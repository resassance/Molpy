package app.musicplayer.storage

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun fixture(
    id: String,
    at: Long = 0,
    source: SourceRef = SourceRef("catalog", "song", id),
) =
    Track(
        TrackId(id),
        "Same title",
        listOf(ArtistRef("Second", SourceRef("catalog", "artist", "a2")), ArtistRef("First", null)),
        AlbumRef("Album", SourceRef("catalog", "album", "album")),
        Artwork(ArtworkKind.RELEASE, "https://art.example/image", null),
        120_000,
        setOf(source),
        MetadataProvenance(source, at, false),
        listOf(
            PlaybackCandidate(
                CandidateId("candidate-$id"),
                SourceRef("playback", "video", id),
                "studio",
                120_000,
                MatchConfidence.HIGH,
            )
        ),
    )

internal class TestLibrary {
    private val directory = Files.createTempDirectory("molpy-library-").toFile()
    private val path = directory.resolve("library.db").absolutePath
    var now = 0L
    var db = createMusicDatabase(path)
    var repository = RoomLibraryRepository(db) { now }

    suspend fun open() {
        db.dao().settingsCount()
    }

    suspend fun reopen() {
        db.close()
        db = createMusicDatabase(path)
        repository = RoomLibraryRepository(db) { now }
        open()
    }

    fun secondDatabase() = createMusicDatabase(path)

    fun close() {
        db.close()
        directory.deleteRecursively()
    }
}

class LibraryPersistenceTest {
    @Test
    fun savingAQueueBatchRollsBackAllTracksIfAnyMetadataIsInvalid() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val a = fixture("batch-a")
                val b = fixture("batch-b")
                assertFailsWith<IllegalArgumentException> {
                    library.repository.upsertAll(
                        listOf(a, b.copy(candidates = b.candidates + b.candidates))
                    )
                }
                assertNull(
                    library.repository.getTrack(a.id),
                    "A failed batch cannot leave partial queue metadata",
                )
                library.repository.upsertAll(listOf(a, b))
                library.reopen()
                assertEquals(a, library.repository.getTrack(a.id))
                assertEquals(b, library.repository.getTrack(b.id))
            } finally {
                library.close()
            }
        }

    @Test
    fun networkProfileDefaultsToSystemAndPersistsOnlyDurableConfiguration() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                assertEquals(
                    NetworkConfig(NetworkMode.SYSTEM, null, null),
                    library.repository.getNetworkConfig(),
                )
                val config =
                    NetworkConfig(
                        NetworkMode.HTTP_CONNECT,
                        ProxyEndpoint("proxy.example", 3128),
                        SecretId("keystore-reference"),
                    )
                library.repository.saveNetworkConfig(config)
                library.reopen()
                assertEquals(config, library.repository.getNetworkConfig())
                val socks =
                    NetworkConfig(NetworkMode.SOCKS5, ProxyEndpoint("socks.example", 1080), null)
                library.repository.saveNetworkConfig(socks)
                assertEquals(socks, library.repository.getNetworkConfig())
                library.repository.saveNetworkConfig(NetworkConfig(NetworkMode.SYSTEM, null, null))
                library.reopen()
                assertEquals(
                    NetworkConfig(NetworkMode.SYSTEM, null, null),
                    library.repository.getNetworkConfig(),
                )
            } finally {
                library.close()
            }
        }

    @Test
    fun identityAndNormalizedAppFieldsSurviveCloseAndReopen() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val source = SourceRef("catalog", "song", "record")
                val id = library.repository.getOrCreate(source)
                val track = fixture(id.value, source = source)
                library.repository.upsert(track)
                library.repository.setFavorite(id, true)
                library.reopen()
                assertEquals(id, library.repository.getOrCreate(source))
                assertEquals(track, library.repository.getTrack(id))
                assertEquals(listOf(track), library.repository.favorites.first())
                library.repository.setFavorite(id, false)
                assertTrue(library.repository.favorites.first().isEmpty())
            } finally {
                library.close()
            }
        }

    @Test
    fun concurrentIdentityAndSourceUpsertsShareOneDurableIdAcrossConnections() =
        runBlocking<Unit> {
            val library = TestLibrary()
            val otherDb = library.secondDatabase()
            try {
                library.open()
                val other = RoomLibraryRepository(otherDb) { 0 }
                val source = SourceRef("catalog", "song", "race")
                val ids = coroutineScope {
                    (0..15)
                        .map { n ->
                            async(Dispatchers.IO) {
                                val repo = if (n % 2 == 0) library.repository else other
                                val id = repo.getOrCreate(source)
                                repo.upsert(fixture(id.value, source = source))
                                id
                            }
                        }
                        .awaitAll()
                }
                assertEquals(1, ids.toSet().size)
                library.reopen()
                assertEquals(ids.first(), library.repository.getOrCreate(source))
                assertEquals(ids.first(), library.repository.getTrack(ids.first())?.id)
            } finally {
                otherDb.close()
                library.close()
            }
        }

    @Test
    fun competingUpsertsForOneSourceKeepItsOriginalIdentity() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val source = SourceRef("catalog", "song", "same-source")
                val id = library.repository.getOrCreate(source)
                coroutineScope {
                    listOf("incoming-one", "incoming-two")
                        .map { incoming ->
                            async(Dispatchers.IO) {
                                library.repository.upsert(fixture(incoming, source = source))
                            }
                        }
                        .awaitAll()
                }
                assertEquals(id, library.repository.getOrCreate(source))
                assertNotNull(library.repository.getTrack(id))
                assertNull(library.repository.getTrack(TrackId("incoming-one")))
                assertNull(library.repository.getTrack(TrackId("incoming-two")))
            } finally {
                library.close()
            }
        }

    @Test
    fun equalTitlesNeverMergeDifferentProviderIdentities() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val a = fixture("a")
                val b = fixture("b")
                library.repository.upsert(a)
                library.repository.upsert(b)
                library.reopen()
                assertNotEquals(
                    library.repository.getOrCreate(a.externalIds.single()),
                    library.repository.getOrCreate(b.externalIds.single()),
                )
                assertEquals(a, library.repository.getTrack(a.id))
                assertEquals(b, library.repository.getTrack(b.id))
            } finally {
                library.close()
            }
        }

    @Test
    fun conflictingCanonicalUpsertPreservesBothTracksAndTheirReferences() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val a = fixture("a").copy(title = "Original A")
                val b = fixture("b").copy(title = "Original B")
                library.repository.upsert(a)
                library.repository.upsert(b)
                library.repository.setFavorite(a.id, true)
                library.repository.recordStarted(b.id, 10)
                val entries =
                    listOf(
                        QueueEntry(QueueEntryId("a-entry"), a.id),
                        QueueEntry(QueueEntryId("b-entry"), b.id),
                    )
                val queue = QueueSnapshot(entries, entries[1].id, 42, false)
                library.repository.saveQueue(queue)
                val addedSource = SourceRef("catalog", "song", "new-source")

                assertFailsWith<IllegalArgumentException> {
                    library.repository.upsert(
                        b.copy(
                            title = "Conflicting B",
                            externalIds = setOf(a.externalIds.single(), addedSource),
                        )
                    )
                }

                library.reopen()
                assertEquals(a, library.repository.getTrack(a.id))
                assertEquals(b, library.repository.getTrack(b.id))
                assertEquals(a.id, library.repository.getOrCreate(a.externalIds.single()))
                assertEquals(b.id, library.repository.getOrCreate(b.externalIds.single()))
                assertNull(
                    library.db
                        .dao()
                        .identity(addedSource.provider, addedSource.kind, addedSource.id)
                )
                assertEquals(listOf(a), library.repository.favorites.first())
                assertEquals(listOf(b), library.repository.recent.first())
                assertEquals(
                    app.musicplayer.queue.QueueOrder.normalized(queue),
                    library.repository.restoreQueue(),
                )
            } finally {
                library.close()
            }
        }

    @Test
    fun canonicalMetadataWithoutExternalIdsCannotBeMergedIntoAnotherTrack() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val a = fixture("metadata-a")
                val b = fixture("metadata-b").copy(externalIds = emptySet())
                library.repository.upsert(a)
                library.repository.upsert(b)

                assertFailsWith<IllegalArgumentException> {
                    library.repository.upsert(b.copy(externalIds = a.externalIds))
                }

                library.reopen()
                assertEquals(a, library.repository.getTrack(a.id))
                assertEquals(b, library.repository.getTrack(b.id))
            } finally {
                library.close()
            }
        }

    @Test
    fun getOrCreateIdentityCannotBeMergedBeforeMetadataExists() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val sourceA = SourceRef("catalog", "song", "identity-a")
                val sourceB = SourceRef("catalog", "song", "identity-b")
                val a = library.repository.getOrCreate(sourceA)
                val b = library.repository.getOrCreate(sourceB)

                assertFailsWith<IllegalArgumentException> {
                    library.repository.upsert(fixture(b.value, source = sourceA))
                }

                library.reopen()
                assertEquals(a, library.repository.getOrCreate(sourceA))
                assertEquals(b, library.repository.getOrCreate(sourceB))
                assertNull(library.repository.getTrack(a))
                assertNull(library.repository.getTrack(b))
                val matching = fixture(b.value, source = sourceB)
                library.repository.upsert(matching)
                assertEquals(matching, library.repository.getTrack(b))
            } finally {
                library.close()
            }
        }

    @Test
    fun prunedCanonicalIdentityCannotBeMergedIntoAnotherTrack() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val a = fixture("pruned-a")
                val b = fixture("pruned-b")
                library.repository.upsert(a)
                library.repository.upsert(b)
                library.now = 7L * 24 * 60 * 60 * 1000
                library.repository.prune(library.now)
                library.reopen()
                assertNull(library.repository.getTrack(a.id))
                assertNull(library.repository.getTrack(b.id))

                assertFailsWith<IllegalArgumentException> {
                    library.repository.upsert(b.copy(externalIds = a.externalIds))
                }

                library.reopen()
                assertEquals(a.id, library.repository.getOrCreate(a.externalIds.single()))
                assertEquals(b.id, library.repository.getOrCreate(b.externalIds.single()))
                assertNull(library.repository.getTrack(a.id))
                assertNull(library.repository.getTrack(b.id))
                library.repository.upsert(b)
                assertEquals(b, library.repository.getTrack(b.id))
            } finally {
                library.close()
            }
        }

    @Test
    fun conflictingSearchBatchRollsBackEarlierMetadataAndCacheWrites() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val a = fixture("batch-a")
                val b = fixture("batch-b")
                val fresh = fixture("batch-fresh")
                library.repository.cacheSearch("query", true, listOf(a, b), 0)

                assertFailsWith<IllegalArgumentException> {
                    library.repository.cacheSearch(
                        "query",
                        true,
                        listOf(fresh, b.copy(externalIds = a.externalIds)),
                        1,
                    )
                }

                library.reopen()
                assertEquals(a, library.repository.getTrack(a.id))
                assertEquals(b, library.repository.getTrack(b.id))
                assertNull(library.repository.getTrack(fresh.id))
                val source = fresh.externalIds.single()
                assertNull(library.db.dao().identity(source.provider, source.kind, source.id))
                assertEquals(listOf(a, b), library.repository.getCachedSearch("query", true, 1))
            } finally {
                library.close()
            }
        }

    @Test
    fun duplicateQueueTracksKeepEntryIdentityAndAlwaysRestartPaused() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val track = fixture("queue")
                library.repository.upsert(track)
                val entries =
                    listOf(
                        QueueEntry(QueueEntryId("first"), track.id),
                        QueueEntry(QueueEntryId("second"), track.id),
                    )
                library.repository.saveQueue(QueueSnapshot(entries, entries[1].id, 32_000, true))
                library.reopen()
                val restored = library.repository.restoreQueue()
                assertEquals(entries, restored.entries)
                assertEquals(entries[1].id, restored.currentEntryId)
                assertEquals(32_000, restored.positionMs)
                assertFalse(restored.playWhenReady)
            } finally {
                library.close()
            }
        }

    @Test
    fun unknownQueueTracksAreDroppedAndMissingCurrentResetsToFirstExistingPaused() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val track = fixture("known")
                library.repository.upsert(track)
                val missing = QueueEntry(QueueEntryId("missing"), TrackId("unknown"))
                val known = QueueEntry(QueueEntryId("known-entry"), track.id)
                library.repository.saveQueue(
                    QueueSnapshot(listOf(missing, known), missing.id, 12_000, true)
                )
                library.reopen()
                assertEquals(
                    app.musicplayer.queue.QueueOrder.normalized(
                        QueueSnapshot(listOf(known), known.id, 0, false)
                    ),
                    library.repository.restoreQueue(),
                )
                library.repository.saveQueue(QueueSnapshot(listOf(missing), missing.id, -20, true))
                assertEquals(
                    app.musicplayer.queue.QueueOrder.normalized(QueueSnapshot()),
                    library.repository.restoreQueue(),
                )
                library.repository.saveQueue(QueueSnapshot(listOf(known), known.id, -20, true))
                assertEquals(0L, library.repository.restoreQueue().positionMs)
                library.repository.saveQueue(QueueSnapshot())
                assertEquals(
                    app.musicplayer.queue.QueueOrder.normalized(QueueSnapshot()),
                    library.repository.restoreQueue(),
                )
            } finally {
                library.close()
            }
        }

    @Test
    fun queueReplacementIsAtomicWhenDuplicateEntryIdsAreRejected() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val track = fixture("queue")
                library.repository.upsert(track)
                val entry = QueueEntry(QueueEntryId("one"), track.id)
                val original = QueueSnapshot(listOf(entry), entry.id, 42, false)
                library.repository.saveQueue(original)
                assertFails {
                    library.repository.saveQueue(
                        QueueSnapshot(listOf(entry, entry), entry.id, 1, true)
                    )
                }
                assertEquals(
                    app.musicplayer.queue.QueueOrder.normalized(original),
                    library.repository.restoreQueue(),
                )
            } finally {
                library.close()
            }
        }

    @Test
    fun lyricsAreBoundToCandidatePersistedAndCannotBeDowngradedByAbsenceOrFailure() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val track = fixture("lyrics")
                library.repository.upsert(track)
                val candidate = track.candidates.single().id
                val synced =
                    LyricsLookup.Available(
                        NormalizedLyrics(
                            "Words",
                            listOf(LyricLine("Words", 0, 1000)),
                            LyricsKind.LINE_SYNCED,
                            SourceRef("lyrics", "entry", "one"),
                        )
                    )
                library.repository.saveLyrics(track.id, candidate, synced, 0)
                library.repository.saveLyrics(track.id, candidate, LyricsLookup.Unavailable, 1)
                library.repository.saveLyrics(
                    track.id,
                    candidate,
                    LyricsLookup.Failed(AppError(ErrorCode.NETWORK, "test", true)),
                    2,
                )
                library.reopen()
                assertEquals(
                    synced,
                    library.repository.getLyrics(track.id, candidate, Long.MAX_VALUE),
                )
                assertNull(
                    library.repository.getLyrics(track.id, CandidateId("different-recording"), 2)
                )
                val second = CandidateId("instrumental")
                val instrumental =
                    LyricsLookup.Instrumental(SourceRef("lyrics", "entry", "instrumental"))
                library.repository.saveLyrics(track.id, second, instrumental, 0)
                assertEquals(instrumental, library.repository.getLyrics(track.id, second, 0))
            } finally {
                library.close()
            }
        }

    @Test
    fun cachedCatalogStoresOnlySuccessesAndSeparatesSongAndVideoQueries() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val track = fixture("catalog")
                var calls = 0
                var answer: AppResult<List<Track>> =
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "test", true))
                val delegate =
                    object : CatalogPort {
                        override suspend fun searchSongs(query: String): AppResult<List<Track>> {
                            calls++
                            return answer
                        }

                        override suspend fun searchVideos(query: String): AppResult<List<Track>> {
                            calls++
                            return AppResult.Success(emptyList())
                        }

                        override suspend fun findMusicMatches(track: Track) = answer
                    }
                val catalog = CachedCatalog(delegate, library.repository) { library.now }
                assertIs<AppResult.Failure>(catalog.searchSongs("query"))
                assertNull(library.repository.getCachedSearch("query", true, 0))
                answer = AppResult.Success(listOf(track))
                assertEquals(answer, catalog.searchSongs("query"))
                assertEquals(track, library.repository.getTrack(track.id))
                assertEquals(answer, catalog.searchSongs("query"))
                assertEquals(2, calls)
                assertEquals(AppResult.Success(emptyList()), catalog.searchVideos("query"))
                assertEquals(3, calls)
                library.reopen()
                assertEquals(listOf(track), library.repository.getCachedSearch("query", true, 0))
                assertEquals(emptyList(), library.repository.getCachedSearch("query", false, 0))
                val match = fixture("match")
                answer = AppResult.Success(listOf(match))
                assertEquals(
                    answer,
                    CachedCatalog(delegate, library.repository) { library.now }
                        .findMusicMatches(track),
                )
                assertEquals(match, library.repository.getTrack(match.id))
            } finally {
                library.close()
            }
        }
}
