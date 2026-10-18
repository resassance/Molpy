package app.musicplayer.ui

import app.musicplayer.model.*

data class TechnicalState(
    val query: String = "",
    val results: List<Track> = emptyList(),
    val selected: Track? = null,
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val lyrics: LyricsLookup? = null,
    val loadingLyrics: Boolean = false,
    val loadingSearch: Boolean = false,
    val searchError: AppError? = null,
)
