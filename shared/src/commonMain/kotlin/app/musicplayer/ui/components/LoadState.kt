package app.musicplayer.ui.components

import app.musicplayer.model.AppError

data class LoadState<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
)
