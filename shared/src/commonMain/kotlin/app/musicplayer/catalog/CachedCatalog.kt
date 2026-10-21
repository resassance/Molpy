package app.musicplayer.catalog

import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException

/** Only normalized successful results enter persistent storage. Failures retain their meaning. */
class CachedCatalog(
    private val delegate: CatalogPort,
    private val repository: LibraryRepository,
    private val nowMs: () -> Long,
) : CatalogPort {
    override suspend fun durations(sources: List<SourceRef>): AppResult<Map<SourceRef, Long>> {
        val result = delegate.durations(sources)
        if (result is AppResult.Success) {
            for ((source, duration) in result.value) {
                try {
                    repository.fillMissingDuration(repository.getOrCreate(source), source, duration)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    /* Optional metadata must not hide visible results. */
                }
            }
        }
        return result
    }

    override suspend fun searchSongs(query: String): AppResult<List<Track>> =
        search(query, true) { delegate.searchSongs(query) }

    override suspend fun searchVideos(query: String): AppResult<List<Track>> =
        search(query, false) { delegate.searchVideos(query) }

    private suspend fun search(
        query: String,
        musicOnly: Boolean,
        fetch: suspend () -> AppResult<List<Track>>,
    ): AppResult<List<Track>> {
        val now = nowMs()
        try {
            repository.getCachedSearch(query, musicOnly, now)?.let {
                return AppResult.Success(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return storageFailure("search.cache.read")
        }
        val result = fetch()
        if (result is AppResult.Success) {
            val savedAt = nowMs()
            try {
                repository.cacheSearch(query, musicOnly, result.value, savedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return storageFailure("search.cache.write")
            }
        }
        return result
    }

    override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> {
        val result = delegate.findMusicMatches(track)
        if (result is AppResult.Success) {
            try {
                result.value.forEach { repository.upsert(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return storageFailure("search.matches.write")
            }
        }
        return result
    }

    private fun storageFailure(operation: String) =
        AppResult.Failure(AppError(ErrorCode.DATABASE, operation, true))
}
