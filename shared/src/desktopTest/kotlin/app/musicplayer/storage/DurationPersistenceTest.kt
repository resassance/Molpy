package app.musicplayer.storage

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class DurationPersistenceTest {
    @Test
    fun fillingDurationRetainsLatestMetadataAndFavorites() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val unknown =
                fixture("duration").let {
                    it.copy(
                        durationMs = null,
                        candidates = it.candidates.map { c -> c.copy(durationMs = null) },
                    )
                }
            library.repository.upsert(unknown)
            library.repository.setFavorite(unknown.id, true)
            val current =
                unknown.copy(
                    title = "Updated title",
                    artwork = Artwork(ArtworkKind.RELEASE, "https://art.example/new", null),
                )
            library.repository.upsert(current)
            val result =
                assertNotNull(
                    library.repository.fillMissingDuration(
                        current.id,
                        current.candidates.first().source,
                        188_000,
                    )
                )
            assertEquals(current.title, result.title)
            assertEquals(current.artwork, result.artwork)
            assertEquals(188_000L, result.durationMs)
            assertEquals(listOf(result), library.repository.favorites.first())
            val unchanged =
                library.repository.fillMissingDuration(
                    current.id,
                    current.candidates.first().source,
                    200_000,
                )
            assertEquals(result, unchanged)
        } finally {
            library.close()
        }
    }

    @Test
    fun repeatedCatalogResultWithoutDurationKeepsKnownDurationsAfterReopen() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val known = fixture("known-duration")
            library.repository.upsert(known)
            val incoming =
                known.copy(
                    title = "Updated title",
                    durationMs = null,
                    candidates = known.candidates.map { it.copy(durationMs = null) },
                )
            library.repository.upsert(incoming)
            library.reopen()
            val saved = assertNotNull(library.repository.getTrack(known.id))
            assertEquals(incoming.title, saved.title)
            assertEquals(known.durationMs, saved.durationMs)
            assertEquals(
                known.candidates.map { it.durationMs },
                saved.candidates.map { it.durationMs },
            )
        } finally {
            library.close()
        }
    }
}
