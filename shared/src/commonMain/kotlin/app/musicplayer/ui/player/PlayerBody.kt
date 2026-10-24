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
    metadata: @Composable () -> Unit,
    transport: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(entryKey) { scroll.scrollTo(0) }
    Layout(
        modifier = modifier,
        content = {
            Box { artwork() }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) { metadata() }
            Box { transport() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = min(8.dp.roundToPx(), height / 10)
        if (width > height * 2) {
            val artworkWidth = min(width / 2, height + 48.dp.roundToPx())
            val cover = children[0].measure(Constraints.fixed(artworkWidth, height))
            val panelWidth = (width - artworkWidth - gap).coerceAtLeast(0)
            val controls =
                children[2].measure(
                    Constraints(minWidth = panelWidth, maxWidth = panelWidth, maxHeight = height)
                )
            val detailsHeight = (height - controls.height).coerceAtLeast(0)
            val details = children[1].measure(Constraints.fixed(panelWidth, detailsHeight))
            layout(width, height) {
                cover.placeRelative(0, 0)
                details.placeRelative(artworkWidth + gap, 0)
                controls.placeRelative(artworkWidth + gap, detailsHeight)
            }
        } else {
            val controls =
                children[2].measure(
                    Constraints(minWidth = width, maxWidth = width, maxHeight = height)
                )
            val available = (height - controls.height - gap).coerceAtLeast(0)
            val preferredFrame =
                min(min((width * 0.76f).toInt(), 360.dp.roundToPx()) + 16.dp.roundToPx(), available)
            val minimumDetails = min(144.dp.roundToPx(), height / 4)
            val minimumFrame =
                if (height >= 500.dp.roundToPx() && fontScale <= 1.1f)
                    min((width * 0.70f).toInt() + 17.dp.roundToPx(), preferredFrame)
                else min(120.dp.roundToPx(), preferredFrame)
            val frameHeight = (available - minimumDetails).coerceIn(minimumFrame, preferredFrame)
            val cover = children[0].measure(Constraints.fixed(width, frameHeight))
            val detailsHeight = (available - frameHeight).coerceAtLeast(0)
            val details = children[1].measure(Constraints.fixed(width, detailsHeight))
            layout(width, height) {
                cover.placeRelative(0, 0)
                details.placeRelative(0, cover.height + gap)
                controls.placeRelative(0, height - controls.height)
            }
        }
    }
}
