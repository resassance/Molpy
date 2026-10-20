package app.musicplayer.network

import android.os.StrictMode
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import app.musicplayer.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.io.*
import java.util.concurrent.*
import java.security.KeyStore
import javax.net.ssl.*

@RunWith(AndroidJUnit4::class)
class ProxyRouteTest {
    @Test fun hostnameProfileActivationDoesNotResolveDnsOnAndroidMain() = runBlocking {
        val androidContext = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = GenerationNetworkController(AndroidSecretStore(androidContext))
        try {
            withContext(Dispatchers.Main) {
                val original = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
                try {
                    for (mode in listOf(NetworkMode.HTTP_CONNECT, NetworkMode.SOCKS5)) {
                        val config = NetworkConfig(mode, ProxyEndpoint("proxy-main-${System.nanoTime()}.test.invalid", 8080), null)
                        assertTrue("Profile apply must not attempt endpoint DNS on Main", controller.apply(config) is AppResult.Success)
                    }
                } finally { StrictMode.setThreadPolicy(original) }
            }
        } finally { controller.close() }
    }

    @Test fun helperWebViewActuallyUsesHttpProxyWithAndWithoutBasicAuthentication() = runBlocking {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
        for (auth in listOf(false, true)) {
            val seen = LinkedBlockingQueue<String>()
            val origins = LinkedBlockingQueue<String>()
            val keys = KeyStore.getInstance("PKCS12").apply {
                load(InstrumentationRegistry.getInstrumentation().context.assets.open("proxy-localhost.p12"), "fixture-only".toCharArray())
            }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
            val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
            val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            val executor = Executors.newCachedThreadPool()
            executor.submit {
                while (!server.isClosed) try {
                    val socket = server.accept()
                    executor.submit { socket.use {
                        it.soTimeout = 5000
                        val reader = it.getInputStream().bufferedReader(); val lines = mutableListOf<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                        val authorized = lines.any { h -> h.equals("Proxy-Authorization: Basic dXNlcjp0ZXN0LXBhc3N3b3Jk", true) }
                        if (auth && !authorized) it.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=molpy-fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        else {
                            seen.put(lines.joinToString("\n"))
                            it.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                            val secure = tls.socketFactory.createSocket(it, "unresolved.test.invalid", 443, false) as SSLSocket
                            secure.use { ssl ->
                                ssl.useClientMode = false; ssl.startHandshake()
                                val originReader = ssl.getInputStream().bufferedReader(); val originLines = mutableListOf<String>()
                                while (true) { val line = originReader.readLine() ?: break; if (line.isEmpty()) break; originLines += line }
                                origins.put(originLines.joinToString("\n"))
                                val body = "<html><title>routed</title>helper</html>"
                                ssl.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            }
                        }
                    } }
                } catch (_: IOException) {}
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config = NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("127.0.0.1", server.localPort), if (auth) SecretId("fixture") else null)
            val active = NetworkContext(1, config, true, true)
            val route = WebViewProxyRoute(context, { active })
            var view: WebView? = null
            try {
                assertTrue(route.apply(active, if (auth) ProxyCredentials("user", "test-password".toCharArray()) else null) is AppResult.Success)
                view = withContext(Dispatchers.Main) {
                    route.createHelperWebView(setOf("unresolved.test.invalid")).apply { loadUrl("https://unresolved.test.invalid/helper") }
                }
                val request = withContext(Dispatchers.IO) { seen.poll(10, TimeUnit.SECONDS) }
                assertNotNull("WebView must reach actual proxy; DNS name is intentionally unresolved", request)
                assertTrue(request!!.startsWith("CONNECT unresolved.test.invalid:443"))
                assertEquals(auth, request.contains("Proxy-Authorization:", true))
                val origin = withContext(Dispatchers.IO) { origins.poll(10, TimeUnit.SECONDS) }
                assertNotNull("TLS-verified helper origin must receive request", origin)
                assertFalse(origin!!.contains("Proxy-Authorization:", true))
            } finally {
                withContext(Dispatchers.Main) { view?.destroy() }
                route.apply(active.copy(config = NetworkConfig(NetworkMode.SYSTEM, null, null)), null)
                server.close(); executor.shutdownNow()
            }
        }
    }
    @Test fun unavailableCapabilityFailsClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = NetworkContext(1, NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("127.0.0.1", 9999), null), false, true)
        val route = WebViewProxyRoute(context, { network }, { false })
        assertEquals(ErrorCode.PROXY_UNSUPPORTED, route.apply(network, null).errorOrNull()?.code)
        assertThrows(IllegalStateException::class.java) { route.createHelperWebView(setOf("example.invalid")) }
    }
}
