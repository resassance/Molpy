package app.musicplayer.network

import app.musicplayer.model.*
import kotlinx.coroutines.flow.StateFlow

interface NetworkController {
    val context: StateFlow<NetworkContext>
    suspend fun apply(config: NetworkConfig): AppResult<NetworkContext>
}
