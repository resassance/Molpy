package app.musicplayer.catalog

import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException

class StoredMusicSearch(
    private val delegate: MusicSearchPort,
    private val repository: LibraryRepository,
) : MusicSearchPort {
    override suspend fun search(query: String, filter: SearchFilter) =
        remember(delegate.search(query, filter))

    override suspend fun more(cursor: CatalogCursor) = remember(delegate.more(cursor))

    private suspend fun remember(result: AppResult<SearchPage>): AppResult<SearchPage> {
        if (result !is AppResult.Success) return result
        return try {
            val tracks = result.value.tracks()
            repository.upsertAll(tracks)
            val saved = tracks.map { repository.getTrack(it.id) ?: it }
            AppResult.Success(result.value.withTracks(saved))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.DATABASE, "search.metadata", true))
        }
    }
}
