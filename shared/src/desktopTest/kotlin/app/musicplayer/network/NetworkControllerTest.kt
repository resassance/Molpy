package app.musicplayer.network

import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class NetworkControllerTest {
    private val system = NetworkConfig(NetworkMode.SYSTEM, null, null)
    private val secrets = object : SecretStore {
        override suspend fun put(username: String, password: CharArray) = SecretId("fixture")
        override suspend fun read(id: SecretId): ProxyCredentials? = null
        override suspend fun remove(id: SecretId) {}
    }
    @Test fun coldStartIsUnreadyUntilProfileAndTransportsAreApplied() = runBlocking {
        val controller = GenerationNetworkController(secrets)
        try {
            assertFalse(controller.context.value.ready)
            val failure = assertFailsWith<AppFailureException> {
                controller.callFactory.newCall(okhttp3.Request.Builder().url("https://example.invalid/").build())
            }
            assertTrue(failure.error.retryable)
            assertIs<AppResult.Success<NetworkContext>>(controller.apply(system))
            assertTrue(controller.context.value.ready)
        } finally { controller.close() }
    }

    @Test fun closeDuringSuspendedApplyCannotResurrectReadyRoute() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = GenerationNetworkController(secrets, activate = { _, _ ->
            entered.complete(Unit); release.await(); AppResult.Success(Unit)
        })
        val applying = async { controller.apply(system) }
        entered.await()
        controller.close()
        release.complete(Unit)
        assertIs<AppResult.Failure>(applying.await())
        assertFalse(controller.context.value.ready)
        assertFails { controller.callFactory.newCall(okhttp3.Request.Builder().url("https://example.invalid/").build()) }
        Unit
    }

    @Test fun closeDuringSuspendedSaveCannotResurrectReadyRoute() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = GenerationNetworkController(secrets, save = {
            entered.complete(Unit); release.await()
        })
        val applying = async { controller.apply(system) }
        entered.await(); controller.close(); release.complete(Unit)
        assertIs<AppResult.Failure>(applying.await())
        assertFalse(controller.context.value.ready)
    }

    @Test fun profileLoadCannotOverwriteNewerUserConfiguration() = runBlocking {
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<NetworkConfig>()
        val controller = GenerationNetworkController(secrets, save = { saved += it })
        try {
            val boot = async { controller.initialize { loading.complete(Unit); release.await(); system } }
            loading.await()
            val proxy = NetworkConfig(NetworkMode.SOCKS5, ProxyEndpoint("localhost", 9050), null)
            val user = async { controller.apply(proxy) }
            yield()
            try { assertFalse(user.isCompleted, "User apply must serialize after initial profile load") }
            finally { release.complete(Unit) }
            boot.await(); user.await()
            assertEquals(proxy, controller.context.value.config)
            assertEquals(proxy, saved.last())
        } finally { release.complete(Unit); controller.close() }
    }

    @Test fun removedLifecycleListenerIsNotActivatedFromCapturedIteration() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = GenerationNetworkController(secrets)
        var callbacks = 0
        val removed = object : NetworkLifecycle {
            override suspend fun activated(context: NetworkContext) { callbacks++ }
        }
        controller.addListener(object : NetworkLifecycle {
            override suspend fun activated(context: NetworkContext) { entered.complete(Unit); release.await() }
        })
        controller.addListener(removed)
        try {
            val apply = async { controller.apply(system) }
            entered.await(); controller.removeListener(removed); release.complete(Unit)
            apply.await()
            assertEquals(0, callbacks)
        } finally { release.complete(Unit); controller.close() }
    }

    @Test fun switchPublishesUnreadyGenerationBeforeActivationAndOnlyThenReady() = runBlocking {
        val entered = CompletableDeferred<NetworkContext>(); val release = CompletableDeferred<Unit>()
        val controller = GenerationNetworkController(secrets, activate = { context, _ -> entered.complete(context); release.await(); AppResult.Success(Unit) })
        try {
            val old = controller.context.value.generation
            val apply = async { controller.apply(NetworkConfig(NetworkMode.SOCKS5, ProxyEndpoint("localhost", 9050), null)) }
            val pending = entered.await()
            assertFalse(pending.ready); assertEquals(old + 1, pending.generation)
            assertEquals(pending, controller.context.value)
            release.complete(Unit)
            val final = apply.await().valueOrNull()!!
            assertTrue(final.ready); assertEquals(pending.generation, final.generation)
        } finally { controller.close() }
    }
    @Test fun failedActivationNeverFallsBackToReadySystemRoute() = runBlocking {
        val controller = GenerationNetworkController(secrets, activate = { _, _ -> AppResult.Failure(AppError(ErrorCode.PROXY_UNSUPPORTED, "webview", false)) })
        try {
            val config = NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("localhost", 8080), null)
            assertEquals(ErrorCode.PROXY_UNSUPPORTED, controller.apply(config).errorOrNull()?.code)
            assertFalse(controller.context.value.ready); assertEquals(config, controller.context.value.config)
            assertFails { controller.callFactory.newCall(okhttp3.Request.Builder().url("https://localhost/").build()).execute() }; Unit
        } finally { controller.close() }
    }
    @Test fun missingCredentialDoesNotStartTransportsAndSettingsContainOnlyReference() = runBlocking {
        var activated = false
        val controller = GenerationNetworkController(secrets, activate = { _, _ -> activated = true; AppResult.Success(Unit) })
        try {
            val config = NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("localhost", 8080), SecretId("safe-reference"))
            assertEquals(ErrorCode.PROXY_AUTH, controller.apply(config).errorOrNull()?.code)
            assertFalse(activated); assertFalse(controller.context.value.ready)
            assertFalse(config.toString().contains("test-password"))
        } finally { controller.close() }
    }
    @Test fun validatorRejectsCredentialedSocksAndProxyUrls() {
        assertEquals(ErrorCode.PROXY_UNSUPPORTED, NetworkConfigValidator.validate(NetworkMode.SOCKS5, "localhost", 1080, SecretId("ref")).errorOrNull()?.code)
        for (host in listOf("https://proxy", "user:password@proxy", "proxy/path", " proxy ")) {
            assertEquals(ErrorCode.INVALID_CONFIG, NetworkConfigValidator.validate(NetworkMode.HTTP_CONNECT, host, 8080, null).errorOrNull()?.code)
        }
    }
}
