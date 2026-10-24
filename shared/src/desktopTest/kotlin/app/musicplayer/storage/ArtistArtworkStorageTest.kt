package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ArtistArtworkStorageTest {
    private fun source(id: String) = SourceRef("youtube", "artist", id)

    private val photo = Artwork(ArtworkKind.RELEASE, "https://example.test/photo", null)

    private fun entry(
        id: String,
        at: Long = 10,
        outcome: ArtistArtworkOutcome = ArtistArtworkOutcome.AVAILABLE,
    ) =
        ArtistArtworkEntry(
            source(id),
            if (outcome == ArtistArtworkOutcome.AVAILABLE) photo else null,
            at,
            at + 100,
            outcome,
        )

    private fun <T> AppResult<T>.value(): T = assertIs<AppResult.Success<T>>(this).value

    @Test
    fun exactSourceRoundtripObserveAndReopenPreserveMetadata() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            val repository = RoomArtistArtworkRepository(store.db)
            val expected = entry("a")
            repository.save(expected).value()
            assertEquals(expected, repository.get(source("a")).value())
            assertEquals(expected, repository.observe(source("a")).first())
            assertNull(repository.get(source("b")).value())
            store.reopen()
            assertEquals(expected, RoomArtistArtworkRepository(store.db).get(source("a")).value())
        } finally {
            store.close()
        }
    }

    @Test
    fun sameArtistNamesNeverCombineSources() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            store.db
                .artistDao()
                .putProfile(ArtistProfileRecord("a", "Same name", "youtube", "artist", "a"))
            store.db
                .artistDao()
                .putProfile(ArtistProfileRecord("b", "Same name", "youtube", "artist", "b"))
            val repository = RoomArtistArtworkRepository(store.db)
            repository.save(entry("a")).value()
            assertNull(repository.get(source("b")).value())
            assertEquals(2, store.db.artistDao().profiles().size)
            assertTrue(store.db.artistDao().merges().isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun retryPreservesLastGoodArtworkAndOlderWritesAreIgnored() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            val repository = RoomArtistArtworkRepository(store.db)
            repository.save(entry("a")).value()
            val retry = entry("a", 20, ArtistArtworkOutcome.RETRY)
            repository.save(retry).value()
            assertEquals(retry.copy(artwork = photo), repository.get(source("a")).value())
            repository.save(entry("a", 15, ArtistArtworkOutcome.ABSENT)).value()
            assertEquals(retry.copy(artwork = photo), repository.get(source("a")).value())
            val absent = entry("a", 30, ArtistArtworkOutcome.ABSENT)
            repository.save(absent).value()
            assertEquals(absent, repository.get(source("a")).value())
        } finally {
            store.close()
        }
    }

    @Test
    fun unknownOrCorruptRowsAreMissesWithDiagnostics() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            val events = mutableListOf<DiagnosticEvent>()
            val repository = RoomArtistArtworkRepository(store.db, events::add)
            val cases =
                listOf(
                    "UNKNOWN" to null,
                    "AVAILABLE" to "broken json",
                    "AVAILABLE" to
                        """{"kind":"UNKNOWN","remoteUrl":"https://example.test/a","localKey":null}""",
                    "AVAILABLE" to
                        """{"kind":"RELEASE","remoteUrl":"https://user:password@example.test/a","localKey":null}""",
                    "AVAILABLE" to null,
                )
            cases.forEachIndexed { index, (outcome, json) ->
                val ref = source("bad$index")
                store.db
                    .artistArtworkDao()
                    .put(
                        ArtistArtworkRecord(
                            ArtistResolver.sourceKey(ref),
                            ref.provider,
                            ref.kind,
                            ref.id,
                            json,
                            10,
                            20,
                            outcome,
                        )
                    )
                assertNull(repository.get(ref).value())
                assertNull(repository.observe(ref).first())
            }
            assertTrue(events.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun writesNormalizeMetadataAndRejectUnsupportedSourcesOrInvalidArtwork() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            val repository = RoomArtistArtworkRepository(store.db)
            repository
                .save(
                    entry("http")
                        .copy(
                            artwork =
                                Artwork(
                                    ArtworkKind.VIDEO,
                                    " http://example.test/photo ",
                                    "private-file",
                                )
                        )
                )
                .value()
            assertEquals(
                Artwork(ArtworkKind.VIDEO, "http://example.test/photo", null),
                repository.get(source("http")).value()?.artwork,
            )
            assertIs<AppResult.Failure>(
                repository.save(entry("a").copy(source = SourceRef("other", "artist", "a")))
            )
            assertIs<AppResult.Failure>(
                repository.save(entry("a").copy(source = SourceRef("youtube", "song", "a")))
            )
            assertIs<AppResult.Failure>(
                repository.save(
                    entry("a")
                        .copy(
                            artwork =
                                Artwork(ArtworkKind.PLACEHOLDER, "https://example.test/a", null)
                        )
                )
            )
            assertIs<AppResult.Failure>(
                repository.save(
                    entry("a")
                        .copy(
                            artwork =
                                Artwork(
                                    ArtworkKind.RELEASE,
                                    "https://user:secret@example.test/a",
                                    null,
                                )
                        )
                )
            )
            assertIs<AppResult.Failure>(
                repository.save(entry("a").copy(artwork = Artwork(ArtworkKind.RELEASE, null, null)))
            )
            assertEquals(1, store.db.artistArtworkDao().count())
        } finally {
            store.close()
        }
    }

    @Test
    fun trimKeeps256NewestSourcesWithoutChangingLibrary() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            store.repository.upsert(fixture("retained"))
            store.repository.setFavorite(TrackId("retained"), true)
            val repository = RoomArtistArtworkRepository(store.db)
            repeat(258) { repository.save(entry("$it", it.toLong())).value() }
            assertNull(repository.get(source("0")).value())
            assertNull(repository.get(source("1")).value())
            assertNotNull(repository.get(source("2")).value())
            assertNotNull(repository.get(source("257")).value())
            assertEquals(256, store.db.artistArtworkDao().count())
            assertEquals(listOf(fixture("retained")), store.repository.favorites.first())
        } finally {
            store.close()
        }
    }
}
