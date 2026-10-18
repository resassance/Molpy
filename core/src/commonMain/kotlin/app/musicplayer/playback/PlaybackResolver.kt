package app.musicplayer.playback

import app.musicplayer.model.*

interface PlaybackResolver {
    suspend fun resolve(
        candidate: PlaybackCandidate,
        capabilities: PlaybackCapabilities,
        network: NetworkContext,
    ): AppResult<ResolvedPlayback>
}
