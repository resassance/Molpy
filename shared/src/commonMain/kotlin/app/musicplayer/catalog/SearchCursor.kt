package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.uuid.Uuid

internal data class SearchCursorContext(
    val query: String,
    val filter: SearchFilter,
    val token: String,
    val seenTokens: Set<String> = emptySet(),
    val seenKeys: Set<String> = emptySet(),
    val emptyPages: Int = 0,
)

internal class SearchCursorStore(private val generation: Long) {
    private val values = linkedMapOf<CatalogCursor, SearchCursorContext>()

    fun put(context: SearchCursorContext): CatalogCursor {
        val cursor = CatalogCursor("$generation:${Uuid.random()}")
        values[cursor] = context
        while (values.size > 128) values.remove(values.keys.first())
        return cursor
    }

    fun get(cursor: CatalogCursor): SearchCursorContext =
        values[cursor]
            ?: throw AppFailureException(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.search.cursor", false)
            )
}
