package app.musicplayer.model

data class AudioMetadataKey(
    val candidateId: CandidateId,
    val source: SourceRef,
    val representationId: String,
) {
    init {
        require(representationId.isNotBlank() && !representationId.contains("://"))
    }
}
