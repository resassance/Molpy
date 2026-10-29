package app.musicplayer.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
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
    val controlsScroll = rememberScrollState()
    LaunchedEffect(entryKey) {
        scroll.scrollTo(0)
        controlsScroll.scrollTo(0)
    }
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale
        val largeText = fontScale > 1.1f
        val landscapeArtworkWidth = minOf(maxWidth * 0.45f, 320.dp)
        val landscapeArtworkHeight = minOf(maxHeight, 160.dp)
        if (maxWidth > maxHeight * 2) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(landscapeArtworkWidth).fillMaxHeight().verticalScroll(scroll)
                ) {
                    metadata()
                    Box(Modifier.fillMaxWidth().height(landscapeArtworkHeight)) { artwork() }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(controlsScroll)) {
                    transport()
                }
            }
        } else if (maxHeight < 440.dp || largeText) {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val upperHeight = maxHeight
                    Row(
                        Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = upperHeight),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(80.dp).height(80.dp)) { artwork() }
                        Box(Modifier.weight(1f)) { metadata() }
                    }
                }
                transport()
            }
        } else {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box { artwork() }
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll)) { metadata() }
                    Box { transport() }
                },
            ) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val gap = min(8.dp.roundToPx(), height / 10)
                val controls =
                    children[2].measure(
                        Constraints(minWidth = width, maxWidth = width, maxHeight = height)
                    )
                val available = (height - controls.height - gap).coerceAtLeast(0)
                val preferredFrame =
                    min(
                        min((width * 0.78f).toInt(), 360.dp.roundToPx()) + 40.dp.roundToPx(),
                        available,
                    )
                val minimumDetails = min(96.dp.roundToPx(), height / 4)
                val frameHeight = (available - minimumDetails).coerceIn(0, preferredFrame)
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
}
