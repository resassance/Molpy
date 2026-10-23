package app.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
fun ShuffleButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconToggleButton(
        checked = enabled,
        onCheckedChange = { onClick() },
        modifier =
            modifier.size(48.dp).semantics {
                contentDescription = "Перемешивание"
                stateDescription = if (enabled) "Включено" else "Выключено"
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                AppIcons.Shuffle,
                null,
                Modifier.size(24.dp),
                tint =
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (enabled)
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(4.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
        }
    }
}
