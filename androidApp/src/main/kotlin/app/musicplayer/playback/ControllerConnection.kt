package app.musicplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ControllerCallbacks(val connected: () -> Unit, val failed: () -> Unit, val disconnected: () -> Unit)

/** Main-thread controller attempt ownership; builder and release operations stay platform supplied. */
internal class ControllerConnection<T : Any>(
    private val create: (ControllerCallbacks) -> T,
    private val release: (T) -> Unit,
) : AutoCloseable {
    private val state = MutableStateFlow(PlayerConnection.Status.DISCONNECTED)
    val status: StateFlow<PlayerConnection.Status> = state.asStateFlow()
    private inner class Attempt { var handle: T? = null }
    private var current: Attempt? = null
    private var closed = false

    fun connect() {
        check(!closed) { "A closed connection must be recreated" }
        if (current != null) return
        val attempt = Attempt()
        current = attempt
        state.value = PlayerConnection.Status.CONNECTING
        val handle = try {
            create(ControllerCallbacks(
                connected = { if (current === attempt) state.value = PlayerConnection.Status.CONNECTED },
                failed = { terminate(attempt, PlayerConnection.Status.FAILED) },
                disconnected = { terminate(attempt, PlayerConnection.Status.DISCONNECTED) },
            ))
        } catch (_: Exception) {
            terminate(attempt, PlayerConnection.Status.FAILED)
            return
        }
        // A builder may fail synchronously, before it returns the handle that must be released.
        if (current === attempt) attempt.handle = handle else release(handle)
    }

    private fun terminate(attempt: Attempt, status: PlayerConnection.Status) {
        if (current !== attempt) return
        current = null
        state.value = status
        attempt.handle?.let(release)
        attempt.handle = null
    }

    override fun close() {
        if (closed) return
        closed = true
        val attempt = current
        current = null
        state.value = PlayerConnection.Status.DISCONNECTED
        attempt?.handle?.let(release)
    }
}
