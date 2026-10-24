package app.musicplayer.artists

import app.musicplayer.model.AppResult
import app.musicplayer.model.SourceRef
import kotlinx.coroutines.flow.Flow

interface ArtistArtworkRepository {
    fun observe(source: SourceRef): Flow<ArtistArtworkEntry?>

    suspend fun get(source: SourceRef): AppResult<ArtistArtworkEntry?>

    suspend fun save(entry: ArtistArtworkEntry): AppResult<Unit>
}
