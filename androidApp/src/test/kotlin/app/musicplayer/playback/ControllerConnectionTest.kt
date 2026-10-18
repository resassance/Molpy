package app.musicplayer.playback

import org.junit.Assert.*
import org.junit.Test

class ControllerConnectionTest {
    private class Fixture {
        val callbacks = mutableListOf<ControllerCallbacks>()
        val released = mutableListOf<Int>()
        val connection = ControllerConnection(create = { callback -> callbacks.add(callback); callbacks.lastIndex }, release = { id: Int -> released.add(id) })
    }
    @Test fun failedBuildCanRetryAndReleasesTerminalAttempt() {
        val f = Fixture()
        f.connection.connect()
        f.callbacks[0].failed()
        assertEquals(PlayerConnection.Status.FAILED, f.connection.status.value)
        f.connection.connect()
        assertEquals(2, f.callbacks.size)
        f.callbacks[1].connected()
        assertEquals(PlayerConnection.Status.CONNECTED, f.connection.status.value)
        assertEquals(listOf(0), f.released)
    }
    @Test fun disconnectedControllerCanReconnect() {
        val f = Fixture()
        f.connection.connect()
        f.callbacks[0].connected()
        f.callbacks[0].disconnected()
        f.connection.connect()
        assertEquals(2, f.callbacks.size)
        f.callbacks[1].connected()
        assertEquals(PlayerConnection.Status.CONNECTED, f.connection.status.value)
        assertEquals(listOf(0), f.released)
    }
    @Test fun staleCallbacksCannotChangeNewAttemptWhileConnectingOrConnected() {
        val f = Fixture()
        f.connection.connect()
        f.callbacks[0].failed()
        f.connection.connect()
        f.callbacks[0].connected()
        f.callbacks[0].disconnected()
        f.callbacks[0].failed()
        assertEquals(PlayerConnection.Status.CONNECTING, f.connection.status.value)
        f.callbacks[1].connected()
        f.callbacks[0].disconnected()
        f.callbacks[0].failed()
        f.callbacks[0].connected()
        assertEquals(PlayerConnection.Status.CONNECTED, f.connection.status.value)
        assertEquals(listOf(0), f.released)
    }
    @Test fun repeatedConnectDoesNotDuplicateActiveAttemptAndCloseReleasesOnce() {
        val f = Fixture()
        f.connection.connect()
        f.connection.connect()
        assertEquals(1, f.callbacks.size)
        f.connection.close()
        f.connection.close()
        f.callbacks[0].connected()
        f.callbacks[0].failed()
        f.callbacks[0].disconnected()
        assertEquals(PlayerConnection.Status.DISCONNECTED, f.connection.status.value)
        assertEquals(listOf(0), f.released)
    }
    @Test fun synchronousFailureDuringConstructionCanRetryAndReleasesReturnedHandle() {
        var attempts = 0
        val released = mutableListOf<Int>()
        val connection = ControllerConnection(create = { callback ->
            val id = attempts++
            if (id == 0) callback.failed() else callback.connected()
            id
        }, release = { id: Int -> released.add(id) })
        connection.connect()
        connection.connect()
        assertEquals(2, attempts)
        assertEquals(PlayerConnection.Status.CONNECTED, connection.status.value)
        assertEquals(listOf(0), released)
    }
}
