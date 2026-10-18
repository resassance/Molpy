package app.musicplayer.fixtures

import app.musicplayer.model.*

object Fixtures {
    fun track(id: String = "t1", title: String = "Numb", artist: String = "Linkin Park", durationMs: Long? = 187000,
              version: String = "studio", sourceId: String = "v1"): Track {
        val metadata = SourceRef("youtube_music", "song", "catalog-$sourceId")
        return Track(TrackId(id), title, listOf(ArtistRef(artist, null)), null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null), durationMs, setOf(metadata),
            MetadataProvenance(metadata, 0, false),
            listOf(PlaybackCandidate(CandidateId("candidate-$sourceId"), SourceRef("youtube", "video", sourceId),
                version, durationMs, MatchConfidence.HIGH)))
    }
}
