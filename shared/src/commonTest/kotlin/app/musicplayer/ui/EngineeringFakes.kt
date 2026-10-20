package app.musicplayer.ui

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.library.LibraryRepository
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.network.*
import app.musicplayer.queue.*
import app.musicplayer.search.SearchCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow

internal fun sampleTrack(title: String = "A"): Track {
    val source = SourceRef("test", "video", title)
    return Track(TrackId(title), title, emptyList(), null, Artwork(ArtworkKind.PLACEHOLDER, null, null),
        60000, setOf(source), MetadataProvenance(source, 0, false),
        listOf(PlaybackCandidate(CandidateId(title), source, "", 60000, MatchConfidence.HIGH)))
}
internal open class EngineeringPort : QueuePlaybackPort {
    override val snapshot = MutableStateFlow(PlaybackSnapshot())
    override val queue = MutableStateFlow(QueueSnapshot())
    val commands = mutableListOf<QueueCommand>()
    val selections = mutableListOf<QueueEntry>()
    var plays = 0
    override suspend fun dispatch(command: QueueCommand) { commands += command; queue.value = QueueReducer.reduce(queue.value, command).snapshot }
    override suspend fun select(track: Track, entry: QueueEntry, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
        selections += entry
        snapshot.value = PlaybackSnapshot(if (playWhenReady) PlayerStatus.PLAYING else PlayerStatus.PAUSED, entry, track, positionMs, track.durationMs, playWhenReady)
        return AppResult.Success(Unit)
    }
    override fun play() { plays++ }
    override fun pause() { snapshot.value = snapshot.value.copy(status = PlayerStatus.PAUSED, playWhenReady = false) }
    override fun seek(positionMs: Long) { }
    override fun stop() { }
}
internal open class EngineeringLibrary : LibraryRepository {
    val tracks = mutableMapOf<TrackId, Track>()
    private val favoritesState = MutableStateFlow<List<Track>>(emptyList())
    override val favorites: Flow<List<Track>> = favoritesState
    override val recent: Flow<List<Track>> = MutableStateFlow(emptyList())
    var config = NetworkConfig(NetworkMode.SYSTEM, null, null)
    override suspend fun upsert(track: Track) { tracks[track.id] = track }
    override suspend fun getTrack(id: TrackId) = tracks[id]
    override suspend fun setFavorite(id: TrackId, favorite: Boolean) { favoritesState.value = favoritesState.value.filterNot { it.id == id } + if (favorite) listOfNotNull(tracks[id]) else emptyList() }
    override suspend fun getNetworkConfig() = config
    override suspend fun saveNetworkConfig(config: NetworkConfig) { this.config = config }
    override suspend fun getOrCreate(source: SourceRef) = TrackId(source.id)
    override suspend fun saveQueue(snapshot: QueueSnapshot) { }
    override suspend fun restoreQueue() = QueueSnapshot()
    override suspend fun recordStarted(id: TrackId, atMs: Long) { }
    override suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>? = null
    override suspend fun cacheSearch(query: String, musicOnly: Boolean, tracks: List<Track>, atMs: Long) { }
    override suspend fun prune(nowMs: Long) { }
    override suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup? = null
    override suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long) { }
}
internal class EngineeringSecrets : SecretStore {
    var puts = 0; var reads = 0
    val removed = mutableListOf<SecretId>()
    override suspend fun put(username: String, password: CharArray): SecretId { puts++; return SecretId("secret-$puts") }
    override suspend fun read(id: SecretId): ProxyCredentials? { reads++; return null }
    override suspend fun remove(id: SecretId) { removed += id }
}
internal open class EngineeringNetwork(val library: EngineeringLibrary) : NetworkController {
    override val context = MutableStateFlow(NetworkContext(0, library.config, true, true))
    val applied = mutableListOf<NetworkConfig>()
    var failAfterSave = false
    var failBeforeSave = false
    override suspend fun apply(config: NetworkConfig): AppResult<NetworkContext> {
        applied += config
        if (failBeforeSave) return AppResult.Failure(AppError(ErrorCode.NETWORK, "test", true))
        library.config = config
        context.value = context.value.copy(generation = context.value.generation + 1, config = config)
        return if (failAfterSave) AppResult.Failure(AppError(ErrorCode.NETWORK, "test", true)) else AppResult.Success(context.value)
    }
}
internal fun engineeringModel(scope: CoroutineScope, port: QueuePlaybackPort = EngineeringPort(),
    library: EngineeringLibrary = EngineeringLibrary(), network: NetworkController = EngineeringNetwork(library),
    secrets: SecretStore = EngineeringSecrets()) = TechnicalScreenModel(SearchCoordinator(object : CatalogPort {
        override suspend fun searchSongs(query: String) = AppResult.Success(emptyList<Track>())
        override suspend fun searchVideos(query: String) = AppResult.Success(emptyList<Track>())
        override suspend fun findMusicMatches(track: Track) = AppResult.Success(emptyList<Track>())
    }), port, LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0L }, library, network, secrets, scope)
