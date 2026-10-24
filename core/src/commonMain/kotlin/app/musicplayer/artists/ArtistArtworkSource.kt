package app.musicplayer.artists

import app.musicplayer.model.AppResult
import app.musicplayer.model.Artwork
import app.musicplayer.model.SourceRef

interface ArtistArtworkSource {
    suspend fun fetch(source: SourceRef): AppResult<Artwork?>
}
