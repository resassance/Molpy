package app.musicplayer.ui

import app.musicplayer.catalog.*
import app.musicplayer.model.*

internal object EmptyPrimaryMusicSearch : MusicSearchPort {
    override suspend fun search(query: String, filter: SearchFilter): AppResult<SearchPage> =
        AppResult.Success(SearchPage(null, emptyList(), null))

    override suspend fun more(cursor: CatalogCursor): AppResult<SearchPage> =
        error("Unexpected pagination")
}
