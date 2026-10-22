package app.musicplayer.artists

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlin.test.*

class ArtistResolverTest {
    private val raw = Fixtures.track()
    private val a = ArtistProfile(ArtistId("a"), "Original", null)
    private val b =
        ArtistProfile(ArtistId("b"), "Selected", SourceRef("youtube", "artist", "selected"))

    @Test
    fun overrideDoesNotRewriteRawTrack() {
        val directory =
            ArtistDirectory(
                profiles = mapOf(a.id to a, b.id to b),
                overrides = mapOf(raw.id to listOf(b.id)),
                revision = 7,
            )
        val shown = ArtistResolver.present(raw, directory)
        assertEquals(raw, shown.base)
        assertEquals(listOf(b.id), shown.artists.map { it.id })
        assertEquals(listOf("Selected"), shown.artists.map { it.name })
        assertEquals(7, shown.revision)
    }

    @Test
    fun unregisteredCreditIsVisibleWithoutFakeIdentity() {
        val credit = ArtistResolver.present(raw, ArtistDirectory()).artists.single()
        assertEquals(raw.artists.single().name, credit.name)
        assertNull(credit.id)
    }

    @Test
    fun exactSourceIdentityIgnoresTrackAndDisplayName() {
        val source = SourceRef("youtube", "artist", "one")
        val x = raw.copy(artists = listOf(ArtistRef("A", source)))
        val y = Fixtures.track("different").copy(artists = listOf(ArtistRef("Renamed", source)))
        assertEquals(ArtistResolver.alias(x, 0), ArtistResolver.alias(y, 0))
        assertNotEquals(
            ArtistResolver.alias(x, 0),
            ArtistResolver.alias(
                y.copy(artists = listOf(ArtistRef("A", source.copy(id = "two")))),
                0,
            ),
        )
    }

    @Test
    fun unlinkedCreditsRemainScopedAndNamesAreNotGloballyMerged() {
        val x = raw.copy(artists = listOf(ArtistRef("  ПИРО\u00a0 КИНЕЗИС  ", null)))
        val y = x.copy(artists = listOf(ArtistRef("пиро кинезис", null)))
        assertEquals(ArtistResolver.alias(x, 0), ArtistResolver.alias(y, 0))
        assertNotEquals(
            ArtistResolver.alias(x, 0),
            ArtistResolver.alias(x.copy(id = TrackId("other")), 0),
        )
        assertNotEquals(
            ArtistResolver.alias(x, 0),
            ArtistResolver.alias(x.copy(artists = listOf(ArtistRef("Другой", null))), 0),
        )
    }

    @Test
    fun keysCannotCollideThroughSeparators() {
        val x = raw.copy(artists = listOf(ArtistRef("A", SourceRef("a/b", "artist", "c"))))
        val y = raw.copy(artists = listOf(ArtistRef("A", SourceRef("a", "b/artist", "c"))))
        assertNotEquals(ArtistResolver.alias(x, 0), ArtistResolver.alias(y, 0))
    }

    @Test
    fun manualNameIsTrimmedAndBoundedWhileSourceNamesRemainIntact() {
        assertEquals("Local", ArtistChoice.Name("  Local  ").name)
        assertFailsWith<IllegalArgumentException> { ArtistChoice.Name(" ") }
        assertFailsWith<IllegalArgumentException> { ArtistChoice.Name("a".repeat(201)) }
        assertEquals(250, ArtistProfile(a.id, "a".repeat(250), null).name.length)
    }
}
