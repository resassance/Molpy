package app.musicplayer.playback

import app.musicplayer.model.*

interface PlaybackResolver {
    fun invalidate(candidateId: CandidateId) {}

    suspend fun resolve(
        candidate: PlaybackCandidate,
        capabilities: PlaybackCapabilities,
        network: NetworkContext,
    ): AppResult<ResolvedPlayback>
}
