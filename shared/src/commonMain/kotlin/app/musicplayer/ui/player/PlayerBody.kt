package app.musicplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun PlayerBody(
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    Layout(
        modifier = modifier,
        content = {
            Box { artwork() }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) { controls() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = min(12.dp.roundToPx(), height / 10)
        val preferredCover = min((width * 0.72f).toInt(), 320.dp.roundToPx())
        val preferredFrame = preferredCover + 16.dp.roundToPx()
        val minimumFrame = min(136.dp.roundToPx(), height / 3)
        val controlsHeight = children[1].minIntrinsicHeight(width)
        val frameHeight =
            (height - controlsHeight - gap).coerceIn(
                minimumFrame,
                preferredFrame.coerceAtLeast(minimumFrame),
            )
        val cover = children[0].measure(Constraints.fixed(width, frameHeight))
        val panel =
            children[1].measure(
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    maxHeight = (height - frameHeight - gap).coerceAtLeast(0),
                )
            )
        layout(width, height) {
            val top = ((height - cover.height - gap - panel.height) / 2).coerceAtLeast(0)
            cover.placeRelative(0, top)
            panel.placeRelative(0, top + cover.height + gap)
        }
    }
}
