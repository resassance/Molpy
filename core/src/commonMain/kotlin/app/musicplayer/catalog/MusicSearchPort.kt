package app.musicplayer.catalog

import app.musicplayer.model.AppResult

interface MusicSearchPort {
    suspend fun search(query: String, filter: SearchFilter): AppResult<SearchPage>

    suspend fun more(cursor: CatalogCursor): AppResult<SearchPage>
}
