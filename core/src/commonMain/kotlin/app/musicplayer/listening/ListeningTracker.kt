package app.musicplayer.listening

import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class ListeningTracker(
    private val repository: ListeningRepository,
    private val snapshots: StateFlow<PlaybackSnapshot>,
    ownerScope: CoroutineScope,
    private val monotonicMs: () -> Long,
    private val wallMs: () -> Long,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val accumulator = ListeningAccumulator(Uuid.random().toString())
    private val pending = linkedMapOf<String, ListeningCheckpoint>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val mutableError = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = mutableError.asStateFlow()
    private var stopped = false
    private var retryAfter = 0L

    init {
        scope.launch {
            snapshots.collect {
                if (!stopped) enqueue(accumulator.observe(it, monotonicMs(), wallMs()))
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                checkpoint()
            }
        }
        scope.launch {
            for (signal in signals) {
                val wait = (retryAfter - monotonicMs()).coerceAtLeast(0)
                if (wait > 0) delay(wait)
                flush()
                if (pending.isNotEmpty() && !stopped) signals.trySend(Unit)
            }
        }
    }

    suspend fun checkpoint() {
        if (stopped) return
        enqueue(accumulator.observe(snapshots.value, monotonicMs(), wallMs()))
        enqueue(accumulator.checkpoint(monotonicMs()))
    }

    fun ownerStopped() {
        if (stopped) return
        enqueue(accumulator.observe(snapshots.value, monotonicMs(), wallMs()))
        enqueue(accumulator.checkpoint(monotonicMs()))
        stopped = true
    }

    suspend fun close() {
        ownerStopped()
        job.cancel()
        withTimeoutOrNull(1000) {
            job.join()
            flush()
        }
        signals.close()
    }

    private fun enqueue(checkpoints: List<ListeningCheckpoint>) {
        for (checkpoint in checkpoints) pending[checkpoint.sessionId] = checkpoint
        if (checkpoints.isNotEmpty()) signals.trySend(Unit)
    }

    private suspend fun flush() {
        while (pending.isNotEmpty()) {
            val checkpoint = pending.values.first()
            val result =
                try {
                    repository.save(checkpoint)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    AppResult.Failure(AppError(ErrorCode.DATABASE, "listening.save", true))
                }
            when (result) {
                is AppResult.Success -> {
                    if (pending[checkpoint.sessionId]?.revision == checkpoint.revision)
                        pending.remove(checkpoint.sessionId)
                    mutableError.value = null
                }
                is AppResult.Failure -> {
                    mutableError.value = result.error
                    retryAfter = monotonicMs() + 5000
                    return
                }
            }
        }
        retryAfter = 0
    }
}
