package app.musicplayer.network

import app.musicplayer.model.*
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import okhttp3.Call
import okhttp3.Request

class MediaTransferTest {
    @Test
    fun progressingBodyOutlivesCallDeadlineWhileMetadataKeepsItsDeadline() {
        val context = NetworkContext(1, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
        ServerSocket(0).use { server ->
            val writer =
                thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val out = socket.getOutputStream()
                        out.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        out.flush()
                        try {
                            repeat(8) {
                                Thread.sleep(100)
                                out.write('a'.code)
                                out.flush()
                            }
                        } catch (_: java.io.IOException) {}
                    }
                }
            NetworkClients(context, null, { context }).use { route ->
                val shortened = Call.Factory { request ->
                    route.callFactory.newCall(request).also {
                        it.timeout().timeout(200, TimeUnit.MILLISECONDS)
                    }
                }
                val media = shortened.forMediaTransfers()
                val cloned =
                    media
                        .newCall(
                            Request.Builder()
                                .url("http://127.0.0.1:${server.localPort}/unused")
                                .build()
                        )
                        .clone()
                assertEquals(0L, cloned.timeout().timeoutNanos())
                media
                    .newCall(
                        Request.Builder().url("http://127.0.0.1:${server.localPort}/audio").build()
                    )
                    .execute()
                    .use { response ->
                        assertEquals("aaaaaaaa", response.body.string())
                    }
                assertEquals(30000, route.okHttp.callTimeoutMillis)
                assertTrue(route.okHttp.readTimeoutMillis > 0)
            }
            writer.join(2000)
        }
    }
}
