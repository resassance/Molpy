package app.musicplayer.ui.artists

import app.musicplayer.artists.ArtistDirectory
import app.musicplayer.model.*
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.flow.*

internal fun <T> Flow<AppResult<T>>.withArtistRevision(
    artists: StateFlow<LoadState<ArtistDirectory>>?,
    revision: (T) -> Long,
): Flow<AppResult<T>> =
    if (artists == null) this
    else
        combine(this, artists) { result, directory ->
                when {
                    directory.error != null -> AppResult.Failure(directory.error)
                    result is AppResult.Failure -> result
                    result is AppResult.Success &&
                        directory.data?.revision == revision(result.value) -> result
                    else -> null
                }
            }
            .filterNotNull()
