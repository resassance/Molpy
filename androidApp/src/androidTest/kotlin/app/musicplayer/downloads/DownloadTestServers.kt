package app.musicplayer.downloads

import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.playback.GeneratedMedia
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.*
import javax.net.ssl.*
import kotlin.math.sin

/** Loopback only. Reuses the existing generated-media server and network TLS certificate asset. */
internal class DownloadTestServers : AutoCloseable {
    private val generated = GeneratedMedia()
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "download-fixture").apply { isDaemon = true } }
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val connections = CopyOnWriteArrayList<Socket>()
    val origins = LinkedBlockingQueue<String>()
    val proxyRequests = LinkedBlockingQueue<String>()
    val destinations = LinkedBlockingQueue<String>()
    private val tls: SSLContext
    val trust: X509TrustManager
    val sslSocketFactory get() = tls.socketFactory
    val redirectUri: String
    init {
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(InstrumentationRegistry.getInstrumentation().context.assets.open("proxy-localhost.p12"), "fixture-only".toCharArray())
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
        trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf(trust), null) }
        val redirect = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        serve(redirect) { socket ->
            origins.put(readHead(socket.getInputStream()))
            socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: ${generated.uri}?signature=test-secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
        }
        redirectUri = "http://127.0.0.1:${redirect.localPort}/expired?signature=test-secret"
    }
    fun audioOrigin(): Int {
        // Original one-second PCM sine; no downloaded media, credentials or live origin.
        val audio = ByteBuffer.allocate(44 + 16000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
            repeat(16000) { putShort((sin(it * 2 * Math.PI * 440 / 16000) * 2000).toInt().toShort()) }
        }.array()
        val origin = tls.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        serve(origin) { socket ->
            val head = readHead(socket.getInputStream()); origins.put(head)
            if (head.lineSequence().first().contains("/start")) {
                socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /audio?signature=test-secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().write(audio)
            }
        }
        return origin.localPort
    }
    fun connectProxy(originPort: Int, port: Int = 0, authorization: String? = null): Int {
        val proxy = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
        serve(proxy) { socket ->
            val head = readHead(socket.getInputStream()); proxyRequests.put(head)
            val actual = head.lineSequence().firstOrNull { it.startsWith("Proxy-Authorization:", true) }?.substringAfter(':')?.trim()
            if (actual != authorization) socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            else Socket("127.0.0.1", originPort).use { target ->
                destinations.put(head.lineSequence().first())
                check(head.startsWith("CONNECT "))
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                socket.getOutputStream().flush(); tunnel(socket, target)
            }
        }
        return proxy.localPort
    }
    fun socksProxy(originPort: Int, port: Int = 0): Int {
        val proxy = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
        serve(proxy) { socket ->
            val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
            check(input.readUnsignedByte() == 5); repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            output.write(byteArrayOf(5, 0)); output.flush()
            check(input.readUnsignedByte() == 5); check(input.readUnsignedByte() == 1); input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 255).toString() }
                else -> error("Expected IPv4 or remote DNS")
            }
            input.readUnsignedShort(); destinations.put(host)
            Socket("127.0.0.1", originPort).use { target ->
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush(); tunnel(socket, target)
            }
        }
        return proxy.localPort
    }
    private fun serve(server: ServerSocket, action: (Socket) -> Unit) {
        servers += server
        pool.submit { while (!server.isClosed) try {
            val socket = server.accept(); connections += socket
            pool.submit { socket.use { runCatching { it.soTimeout = 8000; action(it) }; connections -= it } }
        } catch (_: IOException) { break } }
    }
    private fun tunnel(client: Socket, target: Socket) {
        val outgoing = pool.submit { runCatching { client.getInputStream().copyTo(target.getOutputStream()); target.shutdownOutput() } }
        runCatching { target.getInputStream().copyTo(client.getOutputStream()) }; outgoing.cancel(true)
    }
    override fun close() { servers.forEach { it.close() }; connections.forEach { runCatching { it.close() } }; generated.close(); pool.shutdownNow() }
    private fun readHead(input: InputStream): String {
        val output = ByteArrayOutputStream(); var tail = 0
        while (output.size() < 32768) {
            val byte = input.read(); if (byte < 0) break
            output.write(byte); tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) break
        }
        return output.toString("ISO-8859-1")
    }
}
