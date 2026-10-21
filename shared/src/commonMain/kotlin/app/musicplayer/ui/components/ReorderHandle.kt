package app.musicplayer.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun ReorderHandle(id: String, index: Int, lastIndex: Int, onMove: (Int) -> Unit) {
    val current by rememberUpdatedState(index)
    val last by rememberUpdatedState(lastIndex)
    val move by rememberUpdatedState(onMove)
    val rowHeight = with(LocalDensity.current) { 80.dp.toPx() }
    Icon(
        AppIcons.Grip,
        "Переставить трек",
        Modifier.size(48.dp)
            .pointerInput(id, rowHeight) {
                var distance = 0f
                detectDragGesturesAfterLongPress(
                    onDragEnd = { distance = 0f },
                    onDragCancel = { distance = 0f },
                ) { change, amount ->
                    change.consume()
                    distance += amount.y
                    if (current < 0 || last < current) return@detectDragGesturesAfterLongPress
                    val delta =
                        when {
                            distance > rowHeight -> 1
                            distance < -rowHeight -> -1
                            else -> 0
                        }
                    val target = (current + delta).coerceIn(0, last)
                    if (delta != 0 && target != current) {
                        move(target)
                        distance = 0f
                    }
                }
            }
            .padding(12.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
