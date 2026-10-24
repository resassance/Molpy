package app.musicplayer.ui.theme

import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.musicplayer.appearance.AppTheme

@Suppress("DEPRECATION")
@Composable
internal actual fun ThemeWindowAppearance(theme: AppTheme) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        val darkIcons = theme == AppTheme.LIGHT
        if (Build.VERSION.SDK_INT >= 30) {
            val mask =
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkIcons) mask else 0, mask)
        } else {
            val mask =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    else 0)
            val decor = window.decorView
            decor.systemUiVisibility =
                if (darkIcons) decor.systemUiVisibility or mask
                else decor.systemUiVisibility and mask.inv()
        }
    }
}
