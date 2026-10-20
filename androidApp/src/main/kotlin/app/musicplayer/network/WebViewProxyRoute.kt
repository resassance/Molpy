package app.musicplayer.network

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Native WebView transport only. No helper can start before the native apply callback. */
class WebViewProxyRoute(
    context: Context,
    private val current: () -> NetworkContext,
    private val supportsOverride: () -> Boolean = { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) },
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val views = mutableSetOf<WebView>() // Main looper only.
    @Volatile private var installed: NetworkContext? = null
    private var credentials: ProxyCredentials? = null

    suspend fun invalidate() = withContext(Dispatchers.Main.immediate) {
        installed = null
        credentials?.close(); credentials = null
        views.forEach { it.settings.blockNetworkLoads = true; it.stopLoading(); it.destroy() }
        views.clear()
    }

    suspend fun apply(network: NetworkContext, secret: ProxyCredentials?): AppResult<Unit> = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            invalidate()
            if (NetworkConfigValidator.validate(network.config) is AppResult.Failure)
                return@withContext AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "webview.config", false))
            if (network.config.mode != NetworkMode.SYSTEM && !supportsOverride())
                return@withContext AppResult.Failure(AppError(ErrorCode.PROXY_UNSUPPORTED, "webview.proxy", false))
            if (network.config.credentialRef != null && secret == null)
                return@withContext AppResult.Failure(AppError(ErrorCode.PROXY_AUTH, "webview.credentials", false))
            try {
                if (supportsOverride()) {
                    val controller = ProxyController.getInstance()
                    val mainExecutor = Executor { it.run() }
                    suspendCancellableCoroutine<Unit> { completion ->
                        val done = Runnable { if (completion.isActive) completion.resume(Unit) }
                        if (network.config.mode == NetworkMode.SYSTEM) controller.clearProxyOverride(mainExecutor, done)
                        else {
                            val endpoint = requireNotNull(network.config.endpoint)
                            val host = if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host
                            val scheme = if (network.config.mode == NetworkMode.SOCKS5) "socks5" else "http"
                            val config = ProxyConfig.Builder().addProxyRule("$scheme://$host:${endpoint.port}")
                                .removeImplicitRules().build()
                            controller.setProxyOverride(config, mainExecutor, done)
                        }
                    }
                }
                WebViewDatabase.getInstance(appContext).clearHttpAuthUsernamePassword()
                credentials = secret?.let { supplied ->
                    val password = supplied.password()
                    try { ProxyCredentials(supplied.username, password) } finally { password.fill('\u0000') }
                }
                installed = network
                AppResult.Success(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AppResult.Failure(AppError(ErrorCode.PROXY_UNSUPPORTED, "webview.apply", false)) }
        }
    }

    /** Only HTTPS helper origins are allowed; proxy-host origin auth must never impersonate a 407. */
    fun createHelperWebView(allowedHosts: Set<String>): WebView {
        val applied = checkNotNull(installed) { "Network route has not been applied" }
        check(isCurrent(applied)) { "Network route is not ready" }
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView requires the main thread" }
        val hosts = allowedHosts.map { it.lowercase() }.toSet()
        require(hosts.isNotEmpty() && applied.config.endpoint?.host?.lowercase() !in hosts)
        val gate = ProxyAuthenticationGate(applied)
        fun allowed(uri: Uri) = uri.scheme == "https" && uri.host?.lowercase() in hosts && isCurrent(applied)
        return WebView(appContext).apply {
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !allowed(request.url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (allowed(request.url)) null else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                    val secret = credentials
                    if (secret == null || !gate.allow(current(), host)) { handler.cancel(); return }
                    val password = secret.password()
                    try { handler.proceed(secret.username, String(password)) } finally { password.fill('\u0000') }
                }
                // WebView's default SSL error handler cancels. Never bypass origin verification.
            }
            views += this
        }
    }
    private fun isCurrent(applied: NetworkContext): Boolean = current().let {
        it.ready && it.generation == applied.generation && it.config == applied.config
    }
}
