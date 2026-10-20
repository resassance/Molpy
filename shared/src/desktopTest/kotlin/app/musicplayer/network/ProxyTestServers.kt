package app.musicplayer.network

import java.net.*
import java.io.*
import java.security.KeyStore
import java.util.concurrent.*
import javax.net.ssl.*

/** Loopback fixtures only. The self-signed key is public test data, never a production trust root. */
internal class ProxyTestServers : AutoCloseable {
    val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    val originHeaders = LinkedBlockingQueue<String>()
    val destinations = LinkedBlockingQueue<String>()
    val proxyHeaders = LinkedBlockingQueue<String>()
    val trust: X509TrustManager
    private val tls: SSLContext
    init {
        val keys = KeyStore.getInstance("PKCS12")
        keys.load(javaClass.getResourceAsStream("/network/localhost.p12"), "fixture-only".toCharArray())
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        trust = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), null) }
    }
    val sslSocketFactory get() = tls.socketFactory
    fun origin(tlsEnabled: Boolean = true): Int {
        val server = if (tlsEnabled) tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) else ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serve(server) { socket ->
            val head = readHead(socket.getInputStream())
            originHeaders.put(head)
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
        }
        return server.localPort
    }
    fun httpProxy(originPort: Int, expectedAuthorization: String? = null): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serve(server) { socket ->
            val head = readHead(socket.getInputStream()); proxyHeaders.put(head)
            val auth = head.lines().firstOrNull { it.startsWith("Proxy-Authorization:", true) }?.substringAfter(':')?.trim()
            if (auth != expectedAuthorization) {
                socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else Socket("127.0.0.1", originPort).use { upstream ->
                destinations.put(head.lineSequence().first())
                if (head.startsWith("CONNECT ")) socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                else upstream.getOutputStream().write(head.replace(Regex("(?im)^Proxy-Authorization:.*\\r\\n"), "").toByteArray())
                tunnel(socket, upstream)
            }
        }
        return server.localPort
    }
    fun socks(originPort: Int): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serve(server) { socket ->
            val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
            check(input.readUnsignedByte() == 5); repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            output.write(byteArrayOf(5, 0)); output.flush()
            check(input.readUnsignedByte() == 5); check(input.readUnsignedByte() == 1); input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 255).toString() }
                else -> error("Expected domain destination")
            }
            input.readUnsignedShort(); destinations.put(host)
            Socket("127.0.0.1", originPort).use { upstream ->
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush(); tunnel(socket, upstream)
            }
        }
        return server.localPort
    }
    private fun serve(server: ServerSocket, block: (Socket) -> Unit) {
        sockets += server
        pool.submit { while (!server.isClosed) try { val client = server.accept(); pool.submit { client.use { runCatching { it.soTimeout = 5000; block(it) } } } } catch (_: IOException) {} }
    }
    private fun tunnel(client: Socket, upstream: Socket) {
        val sent = pool.submit { runCatching { client.getInputStream().copyTo(upstream.getOutputStream()); upstream.shutdownOutput() } }
        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }; sent.cancel(true)
    }
    override fun close() { sockets.forEach { it.close() }; pool.shutdownNow() }
    companion object {
        fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (out.size() < 32768) {
                val value = input.read(); if (value < 0) break; out.write(value)
                val bytes = out.toByteArray(); if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
            }
            return out.toString("ISO-8859-1")
        }
    }
}
