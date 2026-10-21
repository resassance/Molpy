package app.musicplayer.ui.components

import app.musicplayer.collections.*
import app.musicplayer.downloads.DownloadPort
import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import app.musicplayer.queue.*
import kotlinx.coroutines.CancellationException

class TrackActions(
    private val library: LibraryRepository,
    private val collections: CollectionsRepository,
    private val player: QueuePlaybackPort,
    private val downloads: DownloadPort,
) {
    suspend fun favorite(track: Track, value: Boolean): AppResult<Unit> = local {
        library.upsert(track)
        library.setFavorite(track.id, value)
    }

    suspend fun enqueue(track: Track): AppResult<Unit> = player.enqueue(track)

    suspend fun append(playlistId: PlaylistId, track: Track): AppResult<Unit> =
        when (val result = collections.append(playlistId, track)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }

    suspend fun download(track: Track, candidate: PlaybackCandidate): AppResult<Unit> {
        if (candidate !in track.candidates)
            return AppResult.Failure(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "download.candidate", false)
            )
        return when (val result = downloads.enqueue(track, candidate)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }
    }

    private suspend fun local(action: suspend () -> Unit): AppResult<Unit> =
        try {
            action()
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.DATABASE, "track.action", true))
        }
}

data class DownloadSelection(val track: Track, val candidate: PlaybackCandidate)

fun selectDownload(track: Track, snapshot: PlaybackSnapshot): DownloadSelection? {
    val actual = snapshot.track?.takeIf { it.id == track.id }
    if (actual != null && snapshot.candidateId != null) {
        return actual.candidates
            .find { it.id == snapshot.candidateId }
            ?.let { DownloadSelection(actual, it) }
    }
    return track.candidates
        .firstOrNull { it.confidence == MatchConfidence.HIGH }
        ?.let { DownloadSelection(track, it) }
}
