package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.uuid.Uuid

internal enum class BrowseKind {
    TRACKS,
    ALBUMS,
}

internal data class BrowseCursorContext(
    val kind: BrowseKind,
    val source: SourceRef,
    val request: BrowseRequest,
    val trackContext: BrowseTrackContext = BrowseTrackContext(),
    val artist: ArtistRef? = null,
    val seenRequests: Set<BrowseRequest> = emptySet(),
    val seenIds: Set<String> = emptySet(),
)

internal class BrowseCursorStore(private val generation: Long) {
    private val entries = linkedMapOf<CatalogCursor, BrowseCursorContext>()

    fun put(context: BrowseCursorContext): CatalogCursor {
        val cursor = CatalogCursor("$generation:${Uuid.random()}")
        entries[cursor] = context
        while (entries.size > 128) entries.remove(entries.keys.first())
        return cursor
    }

    fun get(cursor: CatalogCursor, kind: BrowseKind): BrowseCursorContext {
        val context =
            entries[cursor]
                ?: throw AppFailureException(
                    AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.cursor", false)
                )
        if (context.kind != kind)
            throw AppFailureException(
                AppError(ErrorCode.INVALID_CONFIG, "catalog.cursor.kind", false)
            )
        return context
    }
}
