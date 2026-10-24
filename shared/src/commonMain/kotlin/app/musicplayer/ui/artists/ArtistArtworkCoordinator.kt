package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.catalog.validArtistArtworkSource
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ArtistArtworkState(val artwork: Artwork? = null, val loading: Boolean = false)

class ArtistArtworkCoordinator(
    private val repository: ArtistArtworkRepository,
    private val source: ArtistArtworkSource,
    ownerScope: CoroutineScope,
    private val nowMs: () -> Long,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) {
    private val scope =
        CoroutineScope(
            ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job])
        )
    private val interests = mutableMapOf<Any, Set<SourceRef>>()
    private val entries = linkedMapOf<SourceRef, ArtistArtworkEntry>()
    private val running = mutableMapOf<SourceRef, Job>()
    private val tokens = mutableMapOf<SourceRef, Any>()
    private val mutableState = MutableStateFlow<Map<SourceRef, ArtistArtworkState>>(emptyMap())
    val state: StateFlow<Map<SourceRef, ArtistArtworkState>> = mutableState.asStateFlow()
    private var epoch = 0L
    private var enabled = true
    private var closed = false

    fun updateInterest(owner: Any, sources: Set<SourceRef>) {
        if (closed) return
        interests[owner] = sources.filter(::validArtistArtworkSource).toSet()
        reconcile()
    }

    fun releaseInterest(owner: Any) {
        interests.remove(owner)
        reconcile()
    }

    fun remember(source: SourceRef, artwork: Artwork) {
        if (closed || !validArtistArtworkSource(source) || !validPhoto(artwork)) return
        tokens.remove(source)
        running[source]?.cancel()
        val now = nowMs()
        val entry =
            ArtistArtworkEntry(
                source,
                artwork,
                now,
                now + SUCCESS_TTL,
                ArtistArtworkOutcome.AVAILABLE,
            )
        put(entry)
        publish(source, artwork, false)
        val generation = epoch
        scope.launch {
            if (!closed && generation == epoch && entries[source] === entry) save(entry)
        }
    }

    fun networkInvalidated() {
        if (closed) return
        enabled = false
        epoch++
        tokens.clear()
        running.values.toList().forEach { it.cancel() }
        mutableState.value =
            mutableState.value.mapValues { (_, value) -> value.copy(loading = false) }
    }

    fun networkActivated() {
        if (closed) return
        enabled = true
        reconcile()
    }

    fun close() {
        if (closed) return
        closed = true
        interests.clear()
        tokens.clear()
        scope.cancel()
        mutableState.value =
            mutableState.value.mapValues { (_, value) -> value.copy(loading = false) }
    }

    // Admission follows owner/source insertion order so an oversized viewport cannot churn the
    // cache.
    private fun wanted() =
        interests.values.asSequence().flatten().distinct().take(CACHE_LIMIT).toSet()

    private fun reconcile() {
        if (closed) return
        val visible = wanted()
        mutableState.value = mutableState.value.filterKeys { it in visible }
        running.toMap().forEach { (key, job) ->
            if (key !in visible) {
                tokens.remove(key)
                job.cancel()
                publish(key, state.value[key]?.artwork, false)
            }
        }
        if (!enabled) return
        for (key in visible) {
            if (running.size >= 2) break
            if (key in running) continue
            val cached = entries[key]
            if (cached != null && cached.refreshAfterMs > nowMs()) {
                publish(key, cached.artwork, false)
                continue
            }
            start(key)
        }
    }

    private fun start(key: SourceRef) {
        val token = Any()
        tokens[key] = token
        val generation = epoch
        fun current() =
            !closed && enabled && generation == epoch && tokens[key] === token && key in wanted()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    var cached = entries[key]
                    if (cached == null) {
                        cached = repository.get(key).valueOrNull()
                        if (!current()) return@launch
                        if (cached != null) put(cached)
                    }
                    if (!current()) return@launch
                    publish(key, cached?.artwork, false)
                    if (cached != null && cached.refreshAfterMs > nowMs()) return@launch
                    publish(key, cached?.artwork, true)
                    val result = source.fetch(key)
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@launch
                    val now = nowMs()
                    val entry =
                        when (result) {
                            is AppResult.Success -> {
                                val photo = result.value?.takeIf(::validPhoto)
                                if (result.value != null && photo == null)
                                    ArtistArtworkEntry(
                                        key,
                                        cached?.artwork,
                                        now,
                                        now + RETRY_TTL,
                                        ArtistArtworkOutcome.RETRY,
                                    )
                                else
                                    ArtistArtworkEntry(
                                        key,
                                        photo,
                                        now,
                                        now + if (photo == null) ABSENT_TTL else SUCCESS_TTL,
                                        if (photo == null) ArtistArtworkOutcome.ABSENT
                                        else ArtistArtworkOutcome.AVAILABLE,
                                    )
                            }
                            is AppResult.Failure ->
                                ArtistArtworkEntry(
                                    key,
                                    cached?.artwork,
                                    now,
                                    now + RETRY_TTL,
                                    ArtistArtworkOutcome.RETRY,
                                )
                        }
                    if (!current()) return@launch
                    put(entry)
                    publish(key, entry.artwork, false)
                    if (current() && entries[key] === entry) save(entry)
                } finally {
                    running.remove(key)
                    if (tokens[key] === token) {
                        tokens.remove(key)
                        publish(key, state.value[key]?.artwork, false)
                    }
                    reconcile()
                }
            }
        running[key] = job
        job.start()
    }

    private suspend fun save(entry: ArtistArtworkEntry) {
        when (val result = repository.save(entry)) {
            is AppResult.Failure ->
                diagnostics(
                    DiagnosticEvent(
                        "artist.artwork.cache",
                        "save",
                        null,
                        null,
                        null,
                        result.error.code,
                        0,
                    )
                )
            is AppResult.Success -> Unit
        }
    }

    private fun put(entry: ArtistArtworkEntry) {
        entries.remove(entry.source)
        entries[entry.source] = entry
        val visible = wanted()
        while (entries.size > CACHE_LIMIT) {
            val removable = entries.keys.firstOrNull { it !in visible } ?: entries.keys.first()
            entries.remove(removable)
            mutableState.value = mutableState.value - removable
        }
    }

    private fun publish(source: SourceRef, artwork: Artwork?, loading: Boolean) {
        mutableState.value =
            if (source in wanted()) {
                mutableState.value + (source to ArtistArtworkState(artwork, loading))
            } else {
                mutableState.value - source
            }
    }

    private fun validPhoto(artwork: Artwork) =
        artwork.kind != ArtworkKind.PLACEHOLDER &&
            (!artwork.remoteUrl.isNullOrBlank() || !artwork.localKey.isNullOrBlank())

    private companion object {
        const val CACHE_LIMIT = 256
        const val SUCCESS_TTL = 2_592_000_000L
        const val ABSENT_TTL = 86_400_000L
        const val RETRY_TTL = 300_000L
    }
}
