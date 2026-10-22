package app.musicplayer.artists

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlin.test.*

class ArtistMergeRulesTest {
    private val a = ArtistId("a")
    private val b = ArtistId("b")
    private val c = ArtistId("c")
    private val sa = SourceRef("youtube", "artist", "a")
    private val sb = sa.copy(id = "b")

    private fun directory() =
        ArtistDirectory(
            profiles =
                mapOf(
                    a to ArtistProfile(a, "Same", sa),
                    b to ArtistProfile(b, "Same", sb),
                    c to ArtistProfile(c, "Target", null),
                )
        )

    @Test
    fun sameNamesRemainSeparate() {
        assertNotEquals(ArtistResolver.root(a, directory()), ArtistResolver.root(b, directory()))
    }

    @Test
    fun mergeChainResolvesAndKeepsPreferredSourceWithoutRenamingOriginal() {
        val d =
            directory()
                .copy(merges = mapOf(a to ArtistMerge(a, b, sa, 1), b to ArtistMerge(b, c, sb, 2)))
        assertEquals(c, ArtistResolver.root(a, d))
        assertEquals(ArtistProfile(c, "Target", sb), ArtistResolver.profile(a, d))
        assertEquals(sa, d.profiles.getValue(a).source)
    }

    @Test
    fun splitDiscardsPreferenceFromDetachedGroup() {
        val d = directory().copy(merges = mapOf(b to ArtistMerge(b, c, sb, 2)))
        assertEquals(a, ArtistResolver.root(a, d))
        assertEquals(sb, ArtistResolver.profile(c, d).source)
    }

    @Test
    fun corruptCycleAndMissingTargetAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ArtistResolver.root(
                a,
                directory()
                    .copy(
                        merges =
                            mapOf(a to ArtistMerge(a, b, null, 1), b to ArtistMerge(b, a, null, 2))
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArtistResolver.root(ArtistId("missing"), directory())
        }
    }

    @Test
    fun mergedCollaboratorCreditsAreUniqueAndOverrideSurvivesSplit() {
        val raw = Fixtures.track().copy(artists = listOf(ArtistRef("A", sa), ArtistRef("B", sb)))
        val d =
            directory()
                .copy(
                    aliases =
                        mapOf(ArtistResolver.alias(raw, 0) to a, ArtistResolver.alias(raw, 1) to b),
                    merges = mapOf(a to ArtistMerge(a, b, null, 1)),
                )
        assertEquals(listOf(b), ArtistResolver.present(raw, d).artists.map { it.id })
        val corrected = d.copy(overrides = mapOf(raw.id to listOf(b)), merges = emptyMap())
        assertEquals(listOf(b), ArtistResolver.present(raw, corrected).artists.map { it.id })
    }
}
