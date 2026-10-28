package app.musicplayer.desktop.playback

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal open class MpvException : Exception("mpv connection unavailable")

internal class MpvCommandException : MpvException()

internal interface MpvSession : AutoCloseable {
    val events: SharedFlow<JsonObject>

    suspend fun command(args: List<JsonElement>): JsonElement
}

internal class MpvConnection(
    private val input: InputStream,
    private val output: OutputStream,
    scope: CoroutineScope,
    private val timeoutMs: Long = 5_000,
) : MpvSession {
    private val writerScope =
        CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO
        )
    private val closed = AtomicBoolean()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writes = Mutex()
    private val mutableEvents = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val events = mutableEvents.asSharedFlow()
    private val reader =
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !closed.get()) {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) throw MpvException()
                        if (byte == 10) break
                        if (bytes.size() >= 512 * 1024) throw MpvException()
                        bytes.write(byte)
                    }
                    val message = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val id = message["request_id"]?.jsonPrimitive?.longOrNull
                    if (id != null) {
                        val waiter = pending.remove(id) ?: continue
                        if (message["error"]?.jsonPrimitive?.content == "success")
                            waiter.complete(message["data"] ?: JsonNull)
                        else waiter.completeExceptionally(MpvCommandException())
                    } else if (!mutableEvents.tryEmit(message)) throw MpvException()
                }
            } catch (_: Exception) {
                close()
            }
        }

    override suspend fun command(args: List<JsonElement>): JsonElement {
        if (closed.get() || pending.size >= 64) throw MpvException()
        val id = sequence.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            if (closed.get()) throw MpvException()
            return withTimeout(timeoutMs) {
                writes.withLock {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { close() }
                        writerScope.launch {
                            try {
                                if (closed.get()) throw MpvException()
                                val message = buildJsonObject {
                                    put("command", JsonArray(args))
                                    put("request_id", id)
                                }
                                    .toString()
                                    .toByteArray()
                                if (message.size > 64 * 1024) throw MpvException()
                                output.write(message)
                                output.write(10)
                                output.flush()
                                continuation.resume(Unit)
                            } catch (_: Exception) {
                                continuation.resumeWithException(MpvException())
                            }
                        }
                    }
                }
                deferred.await()
            }
        } catch (e: MpvCommandException) {
            throw e
        } catch (e: CancellationException) {
            close()
            currentCoroutineContext().ensureActive()
            throw MpvException()
        } catch (_: Exception) {
            close()
            throw MpvException()
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(MpvException()) }
        pending.clear()
        writerScope.cancel()
        runCatching { input.close() }
        runCatching { output.close() }
        mutableEvents.tryEmit(buildJsonObject { put("event", "shutdown") })
    }
}

internal suspend fun MpvSession.call(vararg args: String): JsonElement =
    command(args.map(::JsonPrimitive))
