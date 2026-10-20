package app.musicplayer.network

import app.musicplayer.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*

interface NetworkLifecycle {
    suspend fun invalidated(context: NetworkContext) {}
    suspend fun activated(context: NetworkContext) {}
}

/** Activation is transactional across transports. Failure preserves the requested unready route. */
class GenerationNetworkController(
    private val secrets: SecretStore,
    private val activate: suspend (NetworkContext, ProxyCredentials?) -> AppResult<Unit> = { _, _ -> AppResult.Success(Unit) },
    private val save: suspend (NetworkConfig) -> Unit = {},
) : NetworkController, AutoCloseable {
    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val state = MutableStateFlow(NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), false, true))
    override val context = state.asStateFlow()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<NetworkLifecycle>()
    @Volatile private var active: NetworkClients? = null
    @Volatile private var closed = false
    val callFactory: Call.Factory = Call.Factory { request ->
        val route = active
        if (closed || !state.value.ready || route == null) throw AppFailureException(AppError(ErrorCode.NETWORK, "network.route", true))
        route.callFactory.newCall(request)
    }
    /** A stable Ktor/Coil adapter selects the current generation for every new call. */
    private val routingHttp = OkHttpClient.Builder().addInterceptor { chain ->
        try { callFactory.newCall(chain.request()).execute() }
        catch (failure: AppFailureException) { throw java.io.IOException(failure.error.code.name, failure) }
    }.build()
    val http = HttpClient(OkHttp) { engine { preconfigured = routingHttp }; configureProviders() }
    fun callFactoryFor(generation: Long): Call.Factory = Call.Factory { request ->
        if (state.value.generation != generation) throw AppFailureException(AppError(ErrorCode.INVALID_CONFIG, "network.lease", false))
        callFactory.newCall(request)
    }
    fun addListener(listener: NetworkLifecycle) { listeners += listener }
    fun removeListener(listener: NetworkLifecycle) { listeners -= listener }
    fun updateOnline(online: Boolean) { state.update { it.copy(online = online) } }
    /** Loading and user apply share the same queue, so a slow restore cannot overwrite newer input. */
    suspend fun initialize(load: suspend () -> NetworkConfig): AppResult<NetworkContext> = mutex.withLock {
        if (closed) AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "network.closed", false))
        else applyLocked(load())
    }

    override suspend fun apply(config: NetworkConfig): AppResult<NetworkContext> = mutex.withLock { applyLocked(config) }

    private suspend fun applyLocked(config: NetworkConfig): AppResult<NetworkContext> = run {
        if (closed) return@run AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "network.closed", false))
        val pending = state.value.copy(generation = state.value.generation + 1, config = config, ready = false)
        state.value = pending
        routingHttp.dispatcher.cancelAll()
        active?.close(); active = null
        var credentials: ProxyCredentials? = null
        try {
            listeners.forEach { if (it in listeners) it.invalidated(pending) }
            val valid = NetworkConfigValidator.validate(config)
            if (valid is AppResult.Failure) return@run valid
            credentials = config.credentialRef?.let { secrets.read(it) }
            if (config.credentialRef != null && credentials == null) return@run AppResult.Failure(AppError(ErrorCode.PROXY_AUTH, "network.credentials", false))
            when (val applied = activate(pending, credentials)) {
                is AppResult.Failure -> return@run applied
                is AppResult.Success -> Unit
            }
            if (closed) return@run AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "network.closed", false))
            save(config)
            val candidate = NetworkClients(pending, credentials, { state.value })
            val ready = synchronized(lifecycleLock) {
                if (closed) {
                    candidate.close()
                    return@run AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "network.closed", false))
                }
                active = candidate
                state.value.copy(ready = true).also { state.value = it }
            }
            listeners.forEach { if (!closed && it in listeners) it.activated(ready) }
            AppResult.Success(ready)
        } catch (e: CancellationException) { state.update { it.copy(ready = false) }; active?.close(); active = null; throw e }
        catch (e: AppFailureException) { state.update { it.copy(ready = false) }; active?.close(); active = null; AppResult.Failure(e.error) }
        catch (_: Exception) { state.update { it.copy(ready = false) }; active?.close(); active = null; AppResult.Failure(AppError(ErrorCode.NETWORK, "network.apply", true)) }
        finally { credentials?.close() }
    }
    override fun close() = synchronized(lifecycleLock) {
        closed = true; state.update { it.copy(ready = false) }; routingHttp.dispatcher.cancelAll()
        active?.close(); active = null; http.close(); routingHttp.connectionPool.evictAll(); routingHttp.dispatcher.executorService.shutdown()
    }
}
