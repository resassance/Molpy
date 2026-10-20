package app.musicplayer.network

import app.musicplayer.model.*
import kotlin.test.*

class ProxyAuthenticationGateTest {
    private val active = NetworkContext(7, NetworkConfig(NetworkMode.HTTP_CONNECT,
        ProxyEndpoint("proxy.example", 8080), SecretId("fixture")), true, true)

    @Test fun answersConfiguredProxyOnceAndCancelsOriginChallenges() {
        val gate = ProxyAuthenticationGate(active)
        assertFalse(gate.allow(active, "origin.example"))
        assertTrue(gate.allow(active, "PROXY.EXAMPLE"))
        assertFalse(gate.allow(active, "proxy.example"))
    }
    @Test fun staleAndUnreadyHelpersNeverReceiveCredentials() {
        for (current in listOf(active.copy(generation = 8), active.copy(ready = false),
            active.copy(config = NetworkConfig(NetworkMode.SYSTEM, null, null)))) {
            assertFalse(ProxyAuthenticationGate(active).allow(current, "proxy.example"))
        }
        assertFalse(ProxyAuthenticationGate(active.copy(config = active.config.copy(credentialRef = null))).allow(active, "proxy.example"))
    }
}
