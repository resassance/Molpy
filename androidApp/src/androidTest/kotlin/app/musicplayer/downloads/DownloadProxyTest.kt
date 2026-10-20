package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.musicplayer.model.*
import app.musicplayer.network.NetworkClients
import app.musicplayer.network.ProxyCredentials
import kotlinx.coroutines.*
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DownloadProxyTest {
    @Test fun actualMedia3DownloadFailsClosedThenReconnectsThroughSameConnectAndSocksProfile() = runBlocking {
        for (mode in listOf(NetworkMode.HTTP_CONNECT, NetworkMode.SOCKS5)) DownloadTestServers().use { servers ->
            val origin = servers.audioOrigin()
            val proxyPort = ServerSocket(0).use { it.localPort }
            val config = NetworkConfig(mode, ProxyEndpoint("127.0.0.1", proxyPort), if (mode == NetworkMode.HTTP_CONNECT) SecretId("fixture") else null)
            var current = NetworkContext(1, config, true, true)
            fun route() = NetworkClients(current, if (mode == NetworkMode.HTTP_CONNECT) ProxyCredentials("user", "fixture-password".toCharArray()) else null,
                { current }, { sslSocketFactory(servers.sslSocketFactory, servers.trust) })
            var client = route()
            var runtimeHost = "localhost"
            val fixture = NativeDownloadFixture()
            try {
                val factory = DownloadDataSource.Factory(FixtureOfflineRepository(fixture.asset),
                    { AppResult.Success(fixture.lease("https://$runtimeHost:$origin/start?signature=test-secret", current.generation)) },
                    { generation -> check(generation == current.generation); client.callFactory })
                fixture.open(factory)
                fixture.download(Download.STATE_FAILED)
                assertNull("A failed proxy must never fall back to the origin", servers.origins.poll(200, TimeUnit.MILLISECONDS))
                assertEquals(0L, fixture.cache.cacheSpace)
                val oldClient = client
                current = current.copy(generation = 2, ready = false)
                oldClient.close()
                assertThrows(AppFailureException::class.java) { oldClient.callFactory.newCall(okhttp3.Request.Builder().url("https://unresolved.test.invalid:$origin/audio").build()) }
                if (mode == NetworkMode.HTTP_CONNECT) servers.connectProxy(origin, proxyPort, Credentials.basic("user", "fixture-password"))
                else servers.socksProxy(origin, proxyPort)
                runtimeHost = "unresolved.test.invalid"
                current = current.copy(ready = true)
                client = route()
                fixture.download()
                assertTrue(fixture.cache.cacheSpace > 44)
                val first = servers.origins.poll(3, TimeUnit.SECONDS)
                val redirected = servers.origins.poll(3, TimeUnit.SECONDS)
                assertNotNull(first); assertNotNull(redirected)
                assertFalse(first!!.contains("Proxy-Authorization", true))
                assertFalse(redirected!!.contains("Proxy-Authorization", true))
                val destination = servers.destinations.poll(3, TimeUnit.SECONDS)
                if (mode == NetworkMode.SOCKS5) assertEquals("unresolved.test.invalid", destination)
                else assertTrue(destination!!.startsWith("CONNECT unresolved.test.invalid:"))
                fixture.assertNoRuntimeSecrets()
            } finally { client.close(); fixture.close() }
        }
    }
}
