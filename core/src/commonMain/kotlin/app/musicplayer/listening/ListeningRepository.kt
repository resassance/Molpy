package app.musicplayer.listening

import app.musicplayer.model.AppResult
import kotlinx.coroutines.flow.Flow

interface ListeningRepository {
    val profile: Flow<AppResult<ListeningProfile>>

    suspend fun save(checkpoint: ListeningCheckpoint): AppResult<Unit>

    suspend fun history(cursor: HistoryCursor? = null, limit: Int = 50): AppResult<HistoryPage>
}
