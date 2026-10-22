package app.musicplayer.artists

import app.musicplayer.model.*
import kotlinx.coroutines.flow.Flow

interface ArtistRepository {
    val directory: Flow<AppResult<ArtistDirectory>>

    suspend fun ensure(track: Track): AppResult<TrackPresentation>

    suspend fun saveOverride(
        track: Track,
        choices: List<ArtistChoice>,
        expected: List<ArtistId>?,
    ): AppResult<ArtistWriteResult>

    suspend fun resetOverride(
        trackId: TrackId,
        expected: List<ArtistId>?,
    ): AppResult<ArtistWriteResult>

    suspend fun previewMerge(
        origin: ArtistId,
        target: ArtistId,
        preferredSource: SourceRef?,
    ): AppResult<ArtistMergePreview>

    suspend fun merge(preview: ArtistMergePreview): AppResult<ArtistWriteResult>

    suspend fun previewSplit(origin: ArtistId): AppResult<ArtistSplitPreview>

    suspend fun split(preview: ArtistSplitPreview): AppResult<ArtistWriteResult>
}
