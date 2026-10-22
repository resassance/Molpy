package app.musicplayer.artists

import app.musicplayer.model.AppResult

interface ArtistSearchPort {
    suspend fun search(query: String): AppResult<List<ArtistCatalogHit>>

    suspend fun validate(hit: ArtistCatalogHit): AppResult<ArtistCatalogHit>
}
