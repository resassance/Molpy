package app.musicplayer.queue

import app.musicplayer.catalog.PlaylistRowIdentity

internal object PlaylistResumeVerifier {
    fun matches(saved: List<PlaylistRowIdentity>, replay: List<PlaylistRowIdentity>): Boolean =
        saved.size == replay.size &&
            saved.withIndex().all { (index, row) ->
                row.ordinal == index &&
                    (!row.itemId.isNullOrBlank() || row.playbackSource != null) &&
                    row == replay[index]
            }
}
