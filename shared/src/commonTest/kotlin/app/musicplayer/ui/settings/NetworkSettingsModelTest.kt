package app.musicplayer.ui.settings

import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSettingsModelTest {
    @Test
    fun failedRotationRetainsCleanupKnowledgeUntilConfirmedUnreferenced() = runTest {
        for (afterSave in listOf(false, true)) {
            val original = SecretId("original")
            val library =
                EngineeringLibrary().apply {
                    config =
                        NetworkConfig(
                            NetworkMode.HTTP_CONNECT,
                            ProxyEndpoint("old", 8080),
                            original,
                        )
                }
            val network =
                EngineeringNetwork(library).apply {
                    failAfterSave = afterSave
                    failBeforeSave = !afterSave
                }
            val secrets = EngineeringSecrets()
            val model = NetworkSettingsModel(network, library, secrets, backgroundScope)
            runCurrent()
            model.edit(NetworkDraft(NetworkMode.HTTP_CONNECT, "proxy", "8080", true, "user"))
            model.apply("new password".toCharArray())
            assertTrue(secrets.removed.isEmpty())
            network.failAfterSave = false
            network.failBeforeSave = false
            model.edit(NetworkDraft())
            model.apply(null)
            assertEquals(setOf(original, SecretId("secret-1")), secrets.removed.toSet())
            assertNull(library.config.credentialRef)
            model.close()
        }
    }

    @Test
    fun immediateEditingIsNotOverwrittenWhenRestoreJobStartsLater() = runTest {
        val model =
            EngineeringLibrary().let {
                NetworkSettingsModel(
                    EngineeringNetwork(it),
                    it,
                    EngineeringSecrets(),
                    backgroundScope,
                )
            }
        val draft = NetworkDraft(NetworkMode.SOCKS5, "new", "1080")
        model.edit(draft)
        runCurrent()
        assertEquals(draft, model.state.value.draft)
        model.close()
    }

    @Test
    fun secondApplyCannotUnlockEditingDuringFirstApply() = runTest {
        val library = EngineeringLibrary()
        val gate = CompletableDeferred<Unit>()
        val network =
            object : EngineeringNetwork(library) {
                override suspend fun apply(config: NetworkConfig): AppResult<NetworkContext> {
                    gate.await()
                    return super.apply(config)
                }
            }
        val model = NetworkSettingsModel(network, library, EngineeringSecrets(), backgroundScope)
        runCurrent()
        val first = launch { model.apply(null) }
        runCurrent()
        model.apply(null)
        assertTrue(model.state.value.applying)
        gate.complete(Unit)
        first.join()
        model.close()
    }

    @Test
    fun invalidHostAndPortAreHumanErrorsBeforeControllerOrSecretStore() = runTest {
        val library = EngineeringLibrary()
        val network = EngineeringNetwork(library)
        val secrets = EngineeringSecrets()
        val model = NetworkSettingsModel(network, library, secrets, backgroundScope)
        runCurrent()
        listOf("http://proxy" to "8080", "proxy/path" to "8080", "proxy" to "0", "proxy" to "abc")
            .forEach { (host, port) ->
                model.edit(NetworkDraft(NetworkMode.HTTP_CONNECT, host, port, true, "name"))
                val password = "secret".toCharArray()
                model.apply(password)
                assertTrue(password.all { it == '\u0000' })
                assertNotNull(model.state.value.message)
            }
        assertTrue(network.applied.isEmpty())
        assertEquals(0, secrets.puts)
        model.close()
    }

    @Test
    fun applyingRouteReusesCredentialWithoutReadingPassword() = runTest {
        val library = EngineeringLibrary()
        val network = EngineeringNetwork(library)
        val secrets = EngineeringSecrets()
        val model = NetworkSettingsModel(network, library, secrets, backgroundScope)
        runCurrent()
        model.edit(NetworkDraft(NetworkMode.HTTP_CONNECT, " proxy.example ", "8080", true, "user"))
        val password = "secret".toCharArray()
        model.apply(password)
        assertEquals("proxy.example", network.applied.single().endpoint?.host)
        assertTrue(password.all { it == '\u0000' })
        model.apply(null)
        assertEquals(network.applied[0].credentialRef, network.applied[1].credentialRef)
        assertEquals(1, secrets.puts)
        assertEquals(0, secrets.reads)
        model.close()
    }

    @Test
    fun lateProfileRestoreCannotOverwriteEditing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library =
            object : EngineeringLibrary() {
                override suspend fun getNetworkConfig(): NetworkConfig {
                    gate.await()
                    return NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("old", 80), null)
                }
            }
        val model =
            NetworkSettingsModel(
                EngineeringNetwork(library),
                library,
                EngineeringSecrets(),
                backgroundScope,
            )
        runCurrent()
        val edited = NetworkDraft(NetworkMode.SOCKS5, "new", "1080")
        model.edit(edited)
        gate.complete(Unit)
        runCurrent()
        assertEquals(edited, model.state.value.draft)
        model.close()
    }

    @Test
    fun failureAfterPersistenceDoesNotDeleteReferencedSecret() = runTest {
        val library = EngineeringLibrary()
        val network = EngineeringNetwork(library).apply { failAfterSave = true }
        val secrets = EngineeringSecrets()
        val model = NetworkSettingsModel(network, library, secrets, backgroundScope)
        runCurrent()
        model.edit(NetworkDraft(NetworkMode.HTTP_CONNECT, "proxy", "8080", true, "user"))
        model.apply("secret".toCharArray())
        assertNotNull(library.config.credentialRef)
        assertTrue(secrets.removed.isEmpty())
        assertNotNull(model.state.value.error)
        model.close()
    }
}
