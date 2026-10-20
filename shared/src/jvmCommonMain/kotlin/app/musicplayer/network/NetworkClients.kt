package app.musicplayer.network

import app.musicplayer.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One route and generation shared by extraction, catalog, lyrics, Coil and Media3. */
class NetworkClients(
    val context: NetworkContext,
    credentials: ProxyCredentials?,
    private val current: () -> NetworkContext,
    configure: OkHttpClient.Builder.() -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val secret = credentials?.let { original -> original.password().let { password ->
        try { ProxyCredentials(original.username, password) } finally { password.fill('\u0000') }
    } }
    val okHttp: OkHttpClient = OkHttpClient.Builder().apply {
        apply(configure)
        connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        when (context.config.mode) {
            NetworkMode.SYSTEM -> Unit // Inherit OS route and external VPN; do not install NO_PROXY.
            NetworkMode.HTTP_CONNECT, NetworkMode.SOCKS5 -> {
                val endpoint = requireNotNull(context.config.endpoint)
                // Applying a profile may run on Main. Resolve the proxy host only when transport IO connects.
                proxy(Proxy(if (context.config.mode == NetworkMode.HTTP_CONNECT) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)))
            }
        }
        proxyAuthenticator { _, response ->
            // OkHttp asks before every CONNECT with a synthetic challenge, including no-auth proxies.
            if (secret == null && response.challenges().any { it.scheme == "OkHttp-Preemptive" }) return@proxyAuthenticator null
            if (context.config.mode != NetworkMode.HTTP_CONNECT || secret == null ||
                response.request.header("Proxy-Authorization") != null ||
                response.challenges().none { it.scheme.equals("Basic", true) || it.scheme == "OkHttp-Preemptive" }) {
                throw IOException("PROXY_AUTH", AppFailureException(AppError(ErrorCode.PROXY_AUTH, "network.proxy", false)))
            }
            val password = secret.password()
            try { response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(secret.username, String(password))).build() }
            finally { password.fill('\u0000') }
        }
        addInterceptor { chain ->
            try { requireCurrent() } catch (failure: AppFailureException) { throw IOException(failure.error.code.name, failure) }
            val response = chain.proceed(chain.request().newBuilder().removeHeader("Proxy-Authorization").build())
            try { requireCurrent(); response } catch (failure: AppFailureException) { response.close(); throw IOException(failure.error.code.name, failure) }
        }
        eventListenerFactory {
            object : EventListener() {
                override fun callStart(call: Call) { calls += call; if (closed.get()) call.cancel() }
                override fun callEnd(call: Call) { calls -= call }
                override fun callFailed(call: Call, ioe: IOException) { calls -= call }
            }
        }
    }.build()
    val callFactory: Call.Factory = Call.Factory { request -> requireCurrent(); okHttp.newCall(request) }
    val http = HttpClient(OkHttp) { engine { preconfigured = okHttp }; configureProviders() }

    private fun requireCurrent() {
        val active = current()
        if (closed.get() || !active.ready || active.generation != context.generation || active.config != context.config)
            throw AppFailureException(AppError(ErrorCode.INVALID_CONFIG, "network.generation", false))
    }
    /** Optional explicit connectivity probe, never proof of provider reachability or apply readiness. */
    suspend fun probe(url: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try { callFactory.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.code == 407) AppResult.Failure(AppError(ErrorCode.PROXY_AUTH, "network.probe", false))
            else if (it.isSuccessful) AppResult.Success(Unit)
            else AppResult.Failure(AppError(ErrorCode.NETWORK, "network.probe", true))
        } } catch (e: AppFailureException) { AppResult.Failure(e.error) }
        catch (failure: IOException) { AppResult.Failure((failure.cause as? AppFailureException)?.error ?: AppError(ErrorCode.NETWORK, "network.probe", true)) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            calls.forEach { it.cancel() }; calls.clear()
            http.close(); okHttp.dispatcher.cancelAll(); okHttp.connectionPool.evictAll()
            okHttp.dispatcher.executorService.shutdown()
            secret?.close()
        }
    }
}
