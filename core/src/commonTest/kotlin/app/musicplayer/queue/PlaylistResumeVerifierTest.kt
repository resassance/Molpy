package app.musicplayer.queue

import app.musicplayer.catalog.PlaylistRowIdentity
import app.musicplayer.model.SourceRef
import kotlin.test.*

class PlaylistResumeVerifierTest {
    private fun row(index: Int, id: String = "a", item: String? = "row-$index") =
        PlaylistRowIdentity(index, item, SourceRef("youtube", "video", id))

    @Test
    fun exactPrefixIncludesDistinctRepeatedOccurrences() {
        val saved = listOf(row(0), row(1), row(2, "b"))
        assertTrue(PlaylistResumeVerifier.matches(saved, saved))
        assertFalse(
            PlaylistResumeVerifier.matches(
                saved,
                listOf(row(0), row(1, item = "row-0"), row(2, "b")),
            )
        )
    }

    @Test
    fun changedOrderAndTruncatedPrefixAreRejected() {
        val saved = listOf(row(0), row(1, "b"))
        assertFalse(PlaylistResumeVerifier.matches(saved, listOf(row(0, "b"), row(1))))
        assertFalse(PlaylistResumeVerifier.matches(saved, saved.take(1)))
    }

    @Test
    fun sourceAndOrdinalIdentifyRowsWithoutItemIds() {
        val saved = listOf(row(0, item = null), row(1, item = null))
        assertTrue(PlaylistResumeVerifier.matches(saved, saved))
        assertFalse(
            PlaylistResumeVerifier.matches(saved, listOf(row(0, item = null), row(0, item = null)))
        )
    }

    @Test
    fun missingOccurrenceIdentityCannotBeGuessed() {
        val missing = PlaylistRowIdentity(0, null, null)
        assertFalse(PlaylistResumeVerifier.matches(listOf(missing), listOf(missing)))
    }

    @Test
    fun unavailableRowWithStableItemIdCanBeVerified() {
        val unavailable = PlaylistRowIdentity(0, "deleted-row", null)
        assertTrue(PlaylistResumeVerifier.matches(listOf(unavailable), listOf(unavailable)))
        assertFalse(PlaylistResumeVerifier.matches(listOf(row(0)), listOf(unavailable)))
    }
}
