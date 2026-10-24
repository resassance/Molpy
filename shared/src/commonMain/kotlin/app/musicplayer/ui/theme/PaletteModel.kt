package app.musicplayer.ui.theme

import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PaletteModel(
    private val decode: suspend (Artwork) -> AppResult<IntArray>,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val cache = linkedMapOf<Artwork, ArtworkColors>()
    private val mutable = MutableStateFlow(ArtworkPalette.fromPixels(intArrayOf()))
    val state: StateFlow<ArtworkColors> = mutable.asStateFlow()
    private var generation = 0L
    private var request: Job? = null

    fun update(trackId: TrackId?, artwork: Artwork?) {
        update(artwork.takeIf { trackId != null })
    }

    fun update(artwork: Artwork?) {
        val selected = ++generation
        request?.cancel()
        mutable.value = cache[artwork] ?: ArtworkPalette.fromPixels(intArrayOf())
        if (artwork == null || cache.containsKey(artwork)) return
        request = scope.launch {
            val pixels =
                try {
                    decode(artwork).valueOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            ensureActive()
            if (selected != generation) return@launch
            val colors = ArtworkPalette.fromPixels(pixels ?: intArrayOf())
            if (pixels != null) {
                cache[artwork] = colors
                while (cache.size > 32) cache.remove(cache.keys.first())
            }
            mutable.value = colors
        }
    }

    fun close() {
        ++generation
        job.cancel()
    }
}
