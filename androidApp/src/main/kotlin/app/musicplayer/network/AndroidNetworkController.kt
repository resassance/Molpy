package app.musicplayer.network

import android.content.Context
import android.net.*
import android.os.Build
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/** Platform route activation and connectivity. Persistence is injected from the library repository. */
class AndroidNetworkController(
    context: Context,
    secrets: SecretStore,
    private val loadConfig: suspend () -> NetworkConfig,
    saveConfig: suspend (NetworkConfig) -> Unit,
) : NetworkController {
    val webView = WebViewProxyRoute(context, { this.context.value })
    val transport = GenerationNetworkController(secrets, activate = webView::apply, save = saveConfig)
    override val context: StateFlow<NetworkContext> get() = transport.context
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateOnline()
        override fun onLost(network: Network) = updateOnline()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateOnline()
    }
    init {
        transport.addListener(object : NetworkLifecycle {
            override suspend fun invalidated(context: NetworkContext) { webView.invalidate() }
        })
        if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(callback)
        else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        updateOnline()
    }
    suspend fun initialize(): AppResult<NetworkContext> = try { transport.initialize(loadConfig) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AppResult.Failure(AppError(ErrorCode.DATABASE, "network.profile.load", true)) }
    override suspend fun apply(config: NetworkConfig) = transport.apply(config)
    private fun updateOnline() {
        transport.updateOnline(connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }
    suspend fun close() {
        connectivity.unregisterNetworkCallback(callback)
        transport.close()
        webView.invalidate()
    }
}
