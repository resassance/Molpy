package app.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicplayer.playback.PlayerConnection
import app.musicplayer.ui.TechnicalApp
import app.musicplayer.ui.TechnicalScreenModel
import kotlinx.coroutines.*

/** Recreated with the Activity; service playback and its actual Track snapshot outlive this owner. */
class MainActivity : ComponentActivity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connection: PlayerConnection
    private lateinit var model: TechnicalScreenModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MusicApplication
        connection = PlayerConnection(app)
        model = app.graph.createTechnicalModel(connection, screenScope)
        connection.connect()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            TechnicalApp(state, model, app.graph.artwork)
        }
    }

    override fun onDestroy() {
        model.close()
        connection.close()
        screenScope.cancel()
        super.onDestroy()
    }
}
