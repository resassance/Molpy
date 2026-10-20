package app.musicplayer

import android.content.Intent
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
    // Media-session playback continues when permission is denied. UI remains the progress source.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MusicApplication
        connection = PlayerConnection(app)
        model = app.graph.createTechnicalModel(connection, screenScope)
        connection.connect()
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            TechnicalApp(state, model, app.graph.artwork, onManageStorage = {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            })
        }
    }

    override fun onDestroy() {
        model.close()
        connection.close()
        screenScope.cancel()
        super.onDestroy()
    }
}
