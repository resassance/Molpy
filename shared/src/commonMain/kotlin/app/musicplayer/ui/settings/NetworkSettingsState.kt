package app.musicplayer.ui.settings

import app.musicplayer.model.*

data class NetworkDraft(
    val mode: NetworkMode = NetworkMode.SYSTEM,
    val host: String = "",
    val port: String = "",
    val authentication: Boolean = false,
    val username: String = "",
)

data class NetworkSettingsState(
    val draft: NetworkDraft = NetworkDraft(),
    val hasCredential: Boolean = false,
    val applying: Boolean = false,
    val message: String? = null,
    val error: AppError? = null,
)
