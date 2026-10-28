package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.model.SourceRef
import kotlin.test.*

class ArtistPickerCandidatesTest {
    private val origin =
        ArtistProfile(ArtistId("origin"), "pyrokinesis", SourceRef("youtube", "artist", "one"))
    private val duplicate =
        ArtistProfile(
            ArtistId("duplicate"),
            "  Pyrokinesis  ",
            SourceRef("youtube", "artist", "two"),
        )
    private val unrelated = ArtistProfile(ArtistId("other"), "МУККА", null)
    private val directory =
        ArtistDirectory(profiles = listOf(origin, duplicate, unrelated).associateBy { it.id })

    @Test
    fun mergeStartsWithSameNameRatherThanEntireDirectory() {
        assertEquals(
            listOf(duplicate),
            artistPickerCandidates(directory, "", setOf(origin.id), origin.name),
        )
    }

    @Test
    fun explicitSearchCanFindAnotherNameWithoutOfferingSelf() {
        assertEquals(
            listOf(unrelated),
            artistPickerCandidates(directory, "мук", setOf(origin.id), origin.name),
        )
        assertEquals(
            listOf(duplicate),
            artistPickerCandidates(directory, "pyro", setOf(origin.id), origin.name),
        )
    }

    @Test
    fun regularArtistPickerRetainsAllLocalOptionsWithAnEmptyQuery() {
        assertEquals(3, artistPickerCandidates(directory, "", emptySet()).size)
    }

    @Test
    fun alreadyMergedOriginIsExcludedByItsRoot() {
        val merged =
            directory.copy(
                merges =
                    mapOf(origin.id to ArtistMerge(origin.id, duplicate.id, duplicate.source, 1))
            )
        assertEquals(emptyList(), artistPickerCandidates(merged, "", setOf(origin.id), origin.name))
    }
}
