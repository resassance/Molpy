package app.musicplayer.playback

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sin

/** Original ten-second sine wave; no provider media or network fixture is stored. */
internal class GeneratedMedia : AutoCloseable {
    private val audio = ByteBuffer.allocate(44 + 16000 * 10 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
        repeat(160000) { putShort((sin(it * 2 * Math.PI * 440 / 16000) * 2000).toInt().toShort()) }
    }.array()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val uri = "http://127.0.0.1:${server.localPort}/generated.wav"
    @Volatile private var closed = false
    private val worker = thread(name = "generated-wav-http", isDaemon = true) {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                reader.readLine() ?: return@use
                var start = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) start = line.substringAfter("bytes=").substringBefore('-').toInt()
                }
                start = start.coerceIn(0, audio.lastIndex)
                val header = if (start > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${audio.lastIndex}/${audio.size}\r\n" else "HTTP/1.1 200 OK\r\n"
                runCatching {
                    it.getOutputStream().write((header + "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\nContent-Length: ${audio.size - start}\r\nConnection: close\r\n\r\n").toByteArray())
                    it.getOutputStream().write(audio, start, audio.size - start)
                }
            }
        }
    }
    override fun close() { closed = true; server.close(); worker.join(1000) }
}
