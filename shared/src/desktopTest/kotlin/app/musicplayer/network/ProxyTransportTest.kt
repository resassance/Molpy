package app.musicplayer.network

import app.musicplayer.model.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProxyTransportTest {
    private fun context(mode: NetworkMode, port: Int, auth: Boolean = false) = NetworkContext(1,
        NetworkConfig(mode, ProxyEndpoint("127.0.0.1", port), if (auth) SecretId("fixture") else null), true, true)
    private fun client(context: NetworkContext, servers: ProxyTestServers, credentials: ProxyCredentials? = null, current: () -> NetworkContext = { context }) =
        NetworkClients(context, credentials, current, { sslSocketFactory(servers.sslSocketFactory, servers.trust) })

    @Test fun hostnameHttpProxyDefersDnsFromMainLikeConstructionToTransportIo() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "network-main-like") }.asCoroutineDispatcher().use { caller ->
            ProxyTestServers().use { server ->
                val origin = server.origin()
                val numeric = context(NetworkMode.HTTP_CONNECT, server.httpProxy(origin))
                val ctx = numeric.copy(config = numeric.config.copy(endpoint = ProxyEndpoint("localhost", numeric.config.endpoint!!.port)))
                val lookups = LinkedBlockingQueue<Pair<String, String>>()
                withContext(caller) {
                    NetworkClients(ctx, null, { ctx }, {
                        sslSocketFactory(server.sslSocketFactory, server.trust)
                        dns { host -> lookups.put(host to Thread.currentThread().name); listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
                    }).use { route ->
                        assertTrue((route.okHttp.proxy!!.address() as InetSocketAddress).isUnresolved,
                            "Constructing on Main must retain the hostname without synchronous resolution")
                        assertTrue(lookups.isEmpty())
                        assertEquals(AppResult.Success(Unit), route.probe("https://unresolved.test.invalid:$origin/audio"))
                        val lookup = assertNotNull(lookups.poll(3, TimeUnit.SECONDS))
                        assertEquals("localhost", lookup.first)
                        assertNotEquals("network-main-like", lookup.second)
                        assertTrue(server.destinations.poll(3, TimeUnit.SECONDS).startsWith("CONNECT unresolved.test.invalid:"))
                    }
                }
            }
        }
    }

    @Test fun hostnameSocksProxyConnectsOnIoAndKeepsDestinationDnsAtProxy() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "network-main-like") }.asCoroutineDispatcher().use { caller ->
            ProxyTestServers().use { server ->
                val origin = server.origin()
                val numeric = context(NetworkMode.SOCKS5, server.socks(origin))
                val ctx = numeric.copy(config = numeric.config.copy(endpoint = ProxyEndpoint("localhost", numeric.config.endpoint!!.port)))
                withContext(caller) {
                    client(ctx, server).use { route ->
                        assertTrue((route.okHttp.proxy!!.address() as InetSocketAddress).isUnresolved,
                            "SOCKS endpoint resolution belongs to Socket.connect on IO")
                        assertEquals(AppResult.Success(Unit), route.probe("https://unresolved.test.invalid:$origin/audio"))
                        assertEquals("unresolved.test.invalid", server.destinations.poll(3, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test fun endpointDnsFailureOnIoNeverFallsBackToOrigin() = runBlocking {
        ProxyTestServers().use { server ->
            val origin = server.origin()
            val ctx = NetworkContext(1, NetworkConfig(NetworkMode.HTTP_CONNECT, ProxyEndpoint("proxy.test.invalid", 8080), null), true, true)
            NetworkClients(ctx, null, { ctx }, { dns { throw UnknownHostException("fixture proxy DNS failure") } }).use { route ->
                assertEquals(ErrorCode.NETWORK, route.probe("https://localhost:$origin/audio").errorOrNull()?.code)
                assertNull(server.originHeaders.poll(100, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test fun connectNoAuthAndBasicNeverSendCredentialsToOrigin() {
        for (auth in listOf(false, true)) ProxyTestServers().use { server ->
            val origin = server.origin(); val proxy = server.httpProxy(origin, if (auth) Credentials.basic("user", "test-password") else null)
            val ctx = context(NetworkMode.HTTP_CONNECT, proxy, auth)
            client(ctx, server, if (auth) ProxyCredentials("user", "test-password".toCharArray()) else null).use { route ->
                route.callFactory.newCall(Request.Builder().url("https://localhost:$origin/audio").build()).execute().use { assertEquals("ok", it.body!!.string()) }
                assertFalse(server.originHeaders.poll(3, TimeUnit.SECONDS).contains("Proxy-Authorization", true))
            }
        }
    }
    @Test fun socksPassesUnresolvedDestinationToProxy() = ProxyTestServers().use { server ->
        val origin = server.origin(); val proxy = server.socks(origin); val ctx = context(NetworkMode.SOCKS5, proxy)
        client(ctx, server).use { route ->
            route.callFactory.newCall(Request.Builder().url("https://unresolved.test.invalid:$origin/audio").build()).execute().use { assertEquals(200, it.code) }
            assertEquals("unresolved.test.invalid", server.destinations.poll(3, TimeUnit.SECONDS))
        }
    }
    @Test fun unreachableProxyFailsClosed() = ProxyTestServers().use { server ->
        val origin = server.origin(); val port = ServerSocket(0).use { it.localPort }; val ctx = context(NetworkMode.HTTP_CONNECT, port)
        client(ctx, server).use { route ->
            assertFails { route.callFactory.newCall(Request.Builder().url("https://localhost:$origin/audio").build()).execute() }
            assertNull(server.originHeaders.poll(200, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun badPasswordIsTypedAndCredentialsRedacted() = runBlocking {
        ProxyTestServers().use { server ->
            val origin = server.origin(); val proxy = server.httpProxy(origin, Credentials.basic("user", "right")); val ctx = context(NetworkMode.HTTP_CONNECT, proxy, true)
            val secret = ProxyCredentials("user", "test-password".toCharArray())
            assertFalse(secret.toString().contains("test-password")); assertFalse(secret.toString().contains("user"))
            client(ctx, server, secret).use { route ->
                assertEquals(ErrorCode.PROXY_AUTH, route.probe("https://localhost:$origin/").errorOrNull()?.code)
                assertNull(server.originHeaders.poll(200, TimeUnit.MILLISECONDS))
            }
        }
    }
    @Test fun ktorProxyAuthFailureKeepsTypedErrorThroughArtworkBoundary() = runBlocking {
        ProxyTestServers().use { server ->
            val origin = server.origin()
            val ctx = context(NetworkMode.HTTP_CONNECT, server.httpProxy(origin, Credentials.basic("user", "right")), true)
            client(ctx, server, ProxyCredentials("user", "wrong".toCharArray())).use { route ->
                val artwork = app.musicplayer.artwork.ArtworkLoader(coil3.PlatformContext.INSTANCE, route.http)
                try {
                    assertEquals(ErrorCode.PROXY_AUTH, artwork.fetch(Artwork(ArtworkKind.VIDEO, "https://localhost:$origin/cover", null)).errorOrNull()?.code)
                } finally { artwork.imageLoader.shutdown() }
            }
        }
    }

    @Test fun proxyRouteKeepsOriginTlsVerificationEnabled() = ProxyTestServers().use { server ->
        val origin = server.origin()
        val ctx = context(NetworkMode.HTTP_CONNECT, server.httpProxy(origin))
        NetworkClients(ctx, null, { ctx }).use { route ->
            assertFails { route.callFactory.newCall(Request.Builder().url("https://localhost:$origin/audio").build()).execute() }
            assertNull(server.originHeaders.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun oldGenerationClientsCannotOpenNewRequests() = ProxyTestServers().use { server ->
        var current = context(NetworkMode.HTTP_CONNECT, server.httpProxy(server.origin()))
        client(current, server, current = { current }).use { route ->
            current = current.copy(generation = 2, ready = false)
            assertFails { route.callFactory.newCall(Request.Builder().url("https://localhost:1234/").build()).execute() }
            assertNull(server.proxyHeaders.poll(200, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun ktorAdapterUsesSameSocksRoute() = runBlocking {
        ProxyTestServers().use { server ->
            val origin = server.origin(); val ctx = context(NetworkMode.SOCKS5, server.socks(origin))
            client(ctx, server).use { route -> assertEquals("ok", route.http.get("https://unresolved.test.invalid:$origin/lyrics").bodyAsText()) }
            assertEquals("unresolved.test.invalid", server.destinations.poll(3, TimeUnit.SECONDS))
        }
    }
}
