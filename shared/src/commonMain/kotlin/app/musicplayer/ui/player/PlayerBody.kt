package app.musicplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun PlayerBody(
    modifier: Modifier = Modifier,
    entryKey: Any? = null,
    artwork: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(entryKey) { scroll.scrollTo(0) }
    Layout(
        modifier = modifier,
        content = {
            Box { artwork() }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) { controls() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = min(4.dp.roundToPx(), height / 10)
        val preferredCover = min((width * 0.72f).toInt(), 320.dp.roundToPx())
        val minimumFrame = min(120.dp.roundToPx(), height / 3)
        val panel =
            children[1].measure(
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    maxHeight = (height - minimumFrame - gap).coerceAtLeast(0),
                )
            )
        val frameHeight =
            (height - panel.height - gap).coerceIn(
                minimumFrame,
                (preferredCover + 16.dp.roundToPx()).coerceAtLeast(minimumFrame),
            )
        val cover = children[0].measure(Constraints.fixed(width, frameHeight))
        layout(width, height) {
            val top = ((height - cover.height - gap - panel.height) / 2).coerceAtLeast(0)
            cover.placeRelative(0, top)
            panel.placeRelative(0, top + cover.height + gap)
        }
    }
}
