package app.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import app.musicplayer.playback.PlayerConnection
import app.musicplayer.ui.MolpyApp
import app.musicplayer.ui.MolpyModels
import app.musicplayer.ui.navigation.AppNavigation
import kotlinx.coroutines.*

/**
 * Recreated with the Activity; service playback and its actual Track snapshot outlive this owner.
 */
class MainActivity : ComponentActivity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connection: PlayerConnection
    private lateinit var model: MolpyModels
    // Media-session playback continues when permission is denied. UI remains the progress source.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as MusicApplication
        connection = PlayerConnection(app)
        model = app.graph.createModels(connection, screenScope)
        connection.connect()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                savedInstanceState == null &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val navigation =
                rememberSaveable(
                    saver =
                        listSaver<AppNavigation, String>(
                            save = { it.save() },
                            restore = { AppNavigation.restore(it) },
                        )
                ) {
                    AppNavigation()
                }
            val dateFormat =
                androidx.compose.runtime.remember {
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT,
                        java.util.Locale.forLanguageTag("ru"),
                    )
                }
            MolpyApp(
                model,
                app.graph.artwork,
                navigation,
                onManageStorage = {
                    startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                },
                formatDate = { dateFormat.format(java.util.Date(it)) },
                backHandler = { enabled, back -> BackHandler(enabled, onBack = back) },
            )
        }
    }

    override fun onDestroy() {
        model.close()
        connection.close()
        screenScope.cancel()
        super.onDestroy()
    }
}
