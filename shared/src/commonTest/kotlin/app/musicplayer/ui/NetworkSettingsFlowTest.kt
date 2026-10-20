package app.musicplayer.ui
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSettingsFlowTest {
    @Test fun failedRotationRetainsCleanupKnowledgeUntilConfirmedUnreferenced() = runTest {
        for (afterSave in listOf(false, true)) {
            val original = SecretId("original")
            val library = EngineeringLibrary().apply { config = NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("old", 8080), original) }
            val network = EngineeringNetwork(library).apply { failAfterSave = afterSave; failBeforeSave = !afterSave }
            val secrets = EngineeringSecrets()
            val model = engineeringModel(backgroundScope, library = library, network = network, secrets = secrets); runCurrent()
            model.editNetwork(NetworkDraft(NetworkMode.HTTP_CONNECT, "proxy", "8080", true, "user"))
            model.applyNetwork("new password".toCharArray())
            assertTrue(secrets.removed.isEmpty())
            network.failAfterSave = false; network.failBeforeSave = false
            model.editNetwork(NetworkDraft()); model.applyNetwork(null)
            assertEquals(setOf(original, SecretId("secret-1")), secrets.removed.toSet())
            assertNull(library.config.credentialRef); model.close()
        }
    }
    @Test fun immediateEditingIsNotOverwrittenWhenRestoreJobStartsLater() = runTest {
        val model = engineeringModel(backgroundScope)
        val draft = NetworkDraft(NetworkMode.SOCKS5, "new", "1080")
        model.editNetwork(draft); runCurrent()
        assertEquals(draft, model.state.value.networkDraft); model.close()
    }
    @Test fun secondApplyCannotUnlockEditingDuringFirstApply() = runTest {
        val library = EngineeringLibrary(); val gate = CompletableDeferred<Unit>()
        val network = object : EngineeringNetwork(library) {
            override suspend fun apply(config: NetworkConfig): AppResult<NetworkContext> { gate.await(); return super.apply(config) }
        }
        val model = engineeringModel(backgroundScope, library = library, network = network); runCurrent()
        val first = launch { model.applyNetwork(null) }; runCurrent()
        model.applyNetwork(null)
        assertTrue(model.state.value.networkApplying)
        gate.complete(Unit); first.join(); model.close()
    }
    @Test fun invalidHostAndPortAreHumanErrorsBeforeControllerOrSecretStore() = runTest {
        val library = EngineeringLibrary(); val network = EngineeringNetwork(library); val secrets = EngineeringSecrets()
        val model = engineeringModel(backgroundScope, library = library, network = network, secrets = secrets); runCurrent()
        listOf("http://proxy" to "8080", "proxy/path" to "8080", "proxy" to "0", "proxy" to "abc").forEach { (host, port) ->
            model.editNetwork(NetworkDraft(NetworkMode.HTTP_CONNECT, host, port, true, "name"))
            val password = "secret".toCharArray(); model.applyNetwork(password)
            assertTrue(password.all { it == '\u0000' }); assertNotNull(model.state.value.networkMessage)
        }
        assertTrue(network.applied.isEmpty()); assertEquals(0, secrets.puts); model.close()
    }
    @Test fun applyingPausedRouteDoesNotPlayAndReusesCredentialWithoutReadingPassword() = runTest {
        val library = EngineeringLibrary(); val network = EngineeringNetwork(library); val secrets = EngineeringSecrets(); val port = EngineeringPort()
        port.snapshot.value = PlaybackSnapshot(status = PlayerStatus.PAUSED, playWhenReady = false)
        val model = engineeringModel(backgroundScope, port, library, network, secrets); runCurrent()
        model.editNetwork(NetworkDraft(NetworkMode.HTTP_CONNECT, " proxy.example ", "8080", true, "user"))
        val password = "secret".toCharArray(); model.applyNetwork(password)
        assertEquals("proxy.example", network.applied.single().endpoint?.host); assertTrue(password.all { it == '\u0000' })
        model.applyNetwork(null)
        assertEquals(network.applied[0].credentialRef, network.applied[1].credentialRef)
        assertEquals(1, secrets.puts); assertEquals(0, secrets.reads); assertEquals(0, port.plays)
        assertFalse(model.state.value.playback.playWhenReady); model.close()
    }
    @Test fun lateProfileRestoreCannotOverwriteEditing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = object : EngineeringLibrary() { override suspend fun getNetworkConfig(): NetworkConfig { gate.await(); return NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("old", 80), null) } }
        val model = engineeringModel(backgroundScope, library = library); runCurrent()
        val edited = NetworkDraft(NetworkMode.SOCKS5, "new", "1080")
        model.editNetwork(edited); gate.complete(Unit); runCurrent()
        assertEquals(edited, model.state.value.networkDraft); model.close()
    }
    @Test fun failureAfterPersistenceDoesNotDeleteReferencedSecret() = runTest {
        val library = EngineeringLibrary(); val network = EngineeringNetwork(library).apply { failAfterSave = true }; val secrets = EngineeringSecrets()
        val model = engineeringModel(backgroundScope, library = library, network = network, secrets = secrets); runCurrent()
        model.editNetwork(NetworkDraft(NetworkMode.HTTP_CONNECT, "proxy", "8080", true, "user"))
        model.applyNetwork("secret".toCharArray())
        assertNotNull(library.config.credentialRef); assertTrue(secrets.removed.isEmpty()); assertNotNull(model.state.value.networkError)
        model.close()
    }
}
