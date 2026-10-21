package app.musicplayer.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt
import kotlinx.coroutines.*

@Composable
internal fun PlayerSheet(
    visible: Boolean,
    gesturesEnabled: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val motion = remember(scope) { PlayerSheetMotion(scope) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) { motion.show(visible) }
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled && visible) motion.returnToOpen()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val height = with(density) { maxHeight.toPx() }
        val enabled = visible && gesturesEnabled
        fun finish(velocity: Float) {
            motion.finish(velocity, height, density.density) { dismiss() }
        }
        val nested =
            remember(motion, height, enabled, density.density) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset =
                        if (
                            enabled &&
                                source == NestedScrollSource.UserInput &&
                                motion.fraction > 0f
                        )
                            Offset(0f, motion.drag(available.y, height))
                        else Offset.Zero

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset =
                        if (enabled && source == NestedScrollSource.UserInput && available.y > 0f)
                            Offset(0f, motion.drag(available.y, height))
                        else Offset.Zero

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!enabled || motion.fraction <= 0f) return Velocity.Zero
                        finish(available.y)
                        return Velocity(0f, available.y)
                    }
                }
            }
        val handle =
            Modifier.pointerInput(motion, height, enabled, density.density) {
                if (!enabled) return@pointerInput
                val velocity = VelocityTracker()
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        motion.interrupt()
                        velocity.resetTracking()
                        distance = 0f
                    },
                    onDragCancel = { motion.returnToOpen() },
                    onDragEnd = { finish(velocity.calculateVelocity().y) },
                ) { change, amount ->
                    change.consume()
                    distance += amount
                    velocity.addPosition(change.uptimeMillis, Offset(0f, distance))
                    motion.drag(amount, height)
                }
            }
        if (visible || motion.rendered) {
            Surface(
                Modifier.fillMaxSize()
                    .offset { IntOffset(0, (height * motion.fraction).roundToInt()) }
                    .nestedScroll(nested)
                    .semantics { if (!enabled) hideFromAccessibility() }
                    .pointerInput(enabled) {
                        if (!enabled)
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                        it.consume()
                                    }
                                }
                            }
                    }
            ) {
                content(handle)
            }
        }
    }
}

private class PlayerSheetMotion(private val scope: CoroutineScope) {
    var fraction by mutableFloatStateOf(1f)
        private set

    var rendered by mutableStateOf(false)
        private set

    private var open = false
    private var dismissRequested = false
    private var animation: Job? = null

    fun show(value: Boolean) {
        open = value
        dismissRequested = false
        if (value) rendered = true
        move(if (value) 0f else 1f)
    }

    fun interrupt() {
        animation?.cancel()
    }

    fun drag(amount: Float, height: Float): Float {
        if (!open || dismissRequested || height <= 0f) return 0f
        interrupt()
        val previous = fraction
        fraction = (fraction + amount / height).coerceIn(0f, 1f)
        return (fraction - previous) * height
    }

    fun finish(velocity: Float, height: Float, density: Float, onDismiss: () -> Unit) {
        if (!open || dismissRequested) return
        val fast = 1000f * density
        val shouldClose =
            when {
                velocity < -fast -> false
                velocity > fast && fraction * height > 12f * density -> true
                else -> fraction >= 0.22f
            }
        if (shouldClose) {
            dismissRequested = true
            onDismiss()
        } else returnToOpen()
    }

    fun returnToOpen() {
        if (open && !dismissRequested) move(0f)
    }

    private fun move(target: Float) {
        animation?.cancel()
        animation = scope.launch {
            animate(fraction, target, animationSpec = tween(260, easing = FastOutSlowInEasing)) {
                value,
                _ ->
                fraction = value
            }
            if (!open) rendered = false
        }
    }
}
