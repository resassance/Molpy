package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlin.test.*

class ArtistStatisticsAlgorithmTest {
    private val a = ArtistId("a")
    private val b = ArtistId("b")
    private val track = TrackId("track")
    private val directory =
        ArtistDirectory(
            profiles = mapOf(a to ArtistProfile(a, "A", null), b to ArtistProfile(b, "B", null))
        )

    @Test
    fun canonicalArtistReceivesEachSessionOnce() {
        val merged = directory.copy(merges = mapOf(a to ArtistMerge(a, b, null, 1)))
        val result =
            ArtistListeningProjection.aggregate(
                listOf(ArtistSessionSnapshot("one", track, listOf(a, b), 60000, 100000)),
                merged,
            )
        assertEquals(60000L, result.single().playedMs)
        assertEquals(b.value, result.single().key)
    }

    @Test
    fun separateSessionsAreAddedAndUnknownTimeIsExcluded() {
        val sessions =
            listOf(
                ArtistSessionSnapshot("one", track, listOf(a), 60000, 100000),
                ArtistSessionSnapshot("two", track, listOf(a), 40000, 200000),
                ArtistSessionSnapshot("legacy", track, listOf(a), null, 300000),
            )
        val result = ArtistListeningProjection.aggregate(sessions, directory).single()
        assertEquals(100000L, result.playedMs)
        assertEquals(200000L, result.lastPlayedAtMs)
    }

    @Test
    fun emptyOriginalCreditsCanReceiveAnOverride() {
        val d = directory.copy(overrides = mapOf(track to listOf(b)))
        val result =
            ArtistListeningProjection.aggregate(
                    listOf(ArtistSessionSnapshot("one", track, emptyList(), 60000, 90000)),
                    d,
                )
                .single()
        assertEquals(b.value, result.key)
        assertEquals(60000L, result.playedMs)
    }

    @Test
    fun overflowIsRejected() {
        val sessions =
            listOf(
                ArtistSessionSnapshot("one", track, listOf(a), Long.MAX_VALUE, 1),
                ArtistSessionSnapshot("two", track, listOf(a), 1, 2),
            )
        assertFailsWith<IllegalArgumentException> {
            ArtistListeningProjection.aggregate(sessions, directory)
        }
    }
}
