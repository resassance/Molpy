package app.musicplayer.desktop.playback

import java.io.*
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive

internal object MpvProcess {
    fun supportedVersion(version: String): Boolean {
        val match = Regex("mpv v?(\\d+)\\.(\\d+)\\.(\\d+)").find(version) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 0 || minor >= 41
    }

    fun arguments(binary: String, endpoint: String) =
        listOf(
            binary,
            "--no-config",
            "--idle=yes",
            "--no-video",
            "--ytdl=no",
            "--terminal=no",
            "--input-ipc-server=$endpoint",
        )

    suspend fun connect(scope: CoroutineScope): MpvSession =
        deliverMpvSession(Dispatchers.IO) {
            val windows = System.getProperty("os.name").startsWith("Windows", true)
            val directory =
                if (windows) null
                else
                    Files.createTempDirectory(
                        "molpy-mpv-",
                        PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")
                        ),
                    )
            val endpoint =
                if (windows) "\\\\.\\pipe\\molpy-${UUID.randomUUID()}"
                else directory!!.resolve("ipc").toString()
            val binary =
                System.getenv("MOLPY_MPV")?.takeIf { it.isNotBlank() }
                    ?: if (windows) "mpv.exe" else "mpv"
            var process: Process? = null
            var resource: AutoCloseable? = null
            try {
                process =
                    ProcessBuilder(arguments(binary, endpoint))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                val owned = process
                owned.onExit().thenRun {
                    if (directory != null) {
                        runCatching { Files.deleteIfExists(Path.of(endpoint)) }
                        runCatching { Files.deleteIfExists(directory) }
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var input: InputStream? = null
                var output: OutputStream? = null
                while (input == null) {
                    currentCoroutineContext().ensureActive()
                    if (!owned.isAlive || System.nanoTime() > deadline) throw MpvException()
                    try {
                        if (windows) {
                            val file = RandomAccessFile(endpoint, "rw")
                            resource = file
                            input =
                                object : InputStream() {
                                    override fun read() = file.read()

                                    override fun close() = file.close()
                                }
                            output =
                                object : OutputStream() {
                                    override fun write(b: Int) = file.write(b)

                                    override fun write(b: ByteArray) = file.write(b)

                                    override fun close() = file.close()
                                }
                        } else {
                            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                            try {
                                channel.connect(UnixDomainSocketAddress.of(endpoint))
                            } catch (e: Exception) {
                                channel.close()
                                throw e
                            }
                            resource = channel
                            input =
                                object : InputStream() {
                                    override fun read(): Int {
                                        val buffer = ByteBuffer.allocate(1)
                                        return if (channel.read(buffer) < 0) -1
                                        else buffer.array()[0].toInt() and 255
                                    }

                                    override fun close() = channel.close()
                                }
                            output =
                                object : OutputStream() {
                                    override fun write(b: Int) = write(byteArrayOf(b.toByte()))

                                    override fun write(b: ByteArray) {
                                        val buffer = ByteBuffer.wrap(b)
                                        while (buffer.hasRemaining()) channel.write(buffer)
                                    }

                                    override fun close() = channel.close()
                                }
                        }
                    } catch (_: IOException) {
                        delay(50)
                    }
                }
                val connection = MpvConnection(input, requireNotNull(output), scope)
                val version = connection.call("get_property", "mpv-version").jsonPrimitive.content
                if (!supportedVersion(version)) {
                    connection.close()
                    throw MpvException()
                }
                object : MpvSession by connection {
                    private val closed = AtomicBoolean()

                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        connection.close()
                        owned.destroy()
                        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute {
                            if (owned.isAlive) owned.destroyForcibly()
                        }
                    }
                }
            } catch (e: CancellationException) {
                runCatching { resource?.close() }
                process?.destroyForcibly()
                if (process == null && directory != null) Files.deleteIfExists(directory)
                throw e
            } catch (_: Exception) {
                runCatching { resource?.close() }
                process?.destroyForcibly()
                if (process == null && directory != null) Files.deleteIfExists(directory)
                throw MpvException()
            }
        }
}
