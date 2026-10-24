package app.musicplayer.ui.catalog

import app.musicplayer.artists.*
import app.musicplayer.catalog.*
import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.ui.artists.withArtistRevision
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class CatalogSection {
    ALL,
    FAVORITES,
}

enum class CatalogPart {
    TRACKS,
    ALBUMS,
    SINGLES,
}

data class CatalogScreenState(
    val artistId: ArtistId? = null,
    val localTracks: List<Track> = emptyList(),
    val albumRef: AlbumRef? = null,
    val artistRef: ArtistRef? = null,
    val localKey: String? = null,
    val title: String = "",
    val artwork: Artwork? = null,
    val artists: List<ArtistRef> = emptyList(),
    val section: CatalogSection = CatalogSection.ALL,
    val tracks: LoadState<CatalogPage<Track>> = LoadState(),
    val albums: LoadState<CatalogPage<AlbumCard>> = LoadState(),
    val singles: LoadState<CatalogPage<AlbumCard>> = LoadState(),
    val favoriteTracks: List<Track> = emptyList(),
    val collectionError: AppError? = null,
    val commandError: AppError? = null,
)

class CatalogModel(
    private val browse: BrowseCatalog,
    collections: CollectionsRepository,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
    private val artists: StateFlow<LoadState<ArtistDirectory>>? = null,
    private val rememberArtistArtwork: (SourceRef, Artwork) -> Unit = { _, _ -> },
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(CatalogScreenState())
    val state: StateFlow<CatalogScreenState> = mutable.asStateFlow()
    private var commandGeneration = 0L
    private var generation = 0L
    private var request: Job? = null
    private val pages = mutableMapOf<CatalogPart, Job>()
    private var local = LibraryCollections()

    private data class Key(val source: SourceRef?, val local: String?, val album: Boolean)

    private val cache = linkedMapOf<Key, CatalogScreenState>()

    init {
        scope.launch {
            collections.overview
                .withArtistRevision(artists) { it.artistRevision }
                .catch {
                    if (it is CancellationException) throw it
                    emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "catalog.favorites", true)))
                }
                .collect { result ->
                    when (result) {
                        is AppResult.Success -> {
                            local = result.value
                            val current = state.value
                            val directory = currentDirectory()
                            val profile =
                                current.artistId
                                    ?.takeIf { it in directory?.profiles.orEmpty() }
                                    ?.let { ArtistResolver.profile(it, requireNotNull(directory)) }
                            if (
                                profile != null &&
                                    (profile.id != current.artistId ||
                                        profile.source != current.artistRef?.source)
                            )
                                openArtist(profile.id, current.section, force = true)
                            else
                                update {
                                    applyLocal(it)
                                        .copy(
                                            favoriteTracks = favorites(it),
                                            collectionError = null,
                                        )
                                }
                        }
                        is AppResult.Failure -> update { it.copy(collectionError = result.error) }
                    }
                }
        }
    }

    fun localArtist(id: ArtistId) = openArtist(id, CatalogSection.ALL)

    private fun openArtist(id: ArtistId, section: CatalogSection, force: Boolean = false) {
        val directory = artists?.value?.data ?: return
        if (id !in directory.profiles) return
        val profile = ArtistResolver.profile(id, directory)
        open(
            CatalogScreenState(
                artistId = profile.id,
                artistRef = ArtistRef(profile.name, profile.source),
                localKey = profile.id.value,
                title = profile.name,
                section = section,
            ),
            force = force,
        )
    }

    fun album(ref: AlbumRef, localKey: String? = null) =
        open(CatalogScreenState(albumRef = ref, localKey = localKey, title = ref.name))

    fun artist(ref: ArtistRef, localKey: String? = null) {
        val directory = artists?.value?.data
        val id =
            if (directory == null) null
            else {
                val exact =
                    localKey?.let(::ArtistId)?.takeIf { it in directory.profiles }
                        ?: ref.source?.let {
                            directory.aliases[ArtistAliasKey(ArtistResolver.sourceKey(it))]
                        }
                exact
                    ?: local.artists
                        .flatMap { it.tracks }
                        .firstNotNullOfOrNull { track ->
                            track.artists.indices.firstNotNullOfOrNull { index ->
                                if (CollectionGrouping.artistKey(track, index) == localKey)
                                    directory.aliases[ArtistResolver.alias(track, index)]
                                else null
                            }
                        }
            }
        if (id != null) localArtist(id)
        else open(CatalogScreenState(artistRef = ref, localKey = localKey, title = ref.name))
    }

    private fun key(s: CatalogScreenState) =
        Key(s.albumRef?.source ?: s.artistRef?.source, s.localKey, s.albumRef != null)

    private fun open(target: CatalogScreenState, force: Boolean = false) {
        if (!force && key(target) == key(state.value) && state.value.title.isNotBlank()) return
        val selected = ++generation
        request?.cancel()
        pages.values.forEach(Job::cancel)
        pages.clear()
        val cached =
            if (force) null
            else
                cache[key(target)]
                    ?.takeIf { it.tracks.data != null }
                    ?.let {
                        it.copy(
                            tracks = it.tracks.copy(loading = false),
                            albums = it.albums.copy(loading = false),
                            singles = it.singles.copy(loading = false),
                        )
                    }
        val next = cached ?: target
        mutable.value = applyLocal(next).let { it.copy(favoriteTracks = favorites(it)) }
        if (cached != null) return
        if (target.albumRef?.source == null && target.artistRef?.source == null) {
            if (target.artistId != null) {
                update { it.copy(tracks = LoadState(CatalogPage(emptyList(), null))) }
                return
            }
            update {
                it.copy(
                    tracks =
                        LoadState(
                            error = AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.source", false)
                        )
                )
            }
            return
        }
        update { it.copy(tracks = it.tracks.copy(loading = true, error = null)) }
        request = scope.launch {
            try {
                val result =
                    if (target.albumRef != null) browse.album(target.albumRef)
                    else browse.artist(requireNotNull(target.artistRef))
                ensureActive()
                if (selected != generation) return@launch
                when (result) {
                    is AppResult.Failure ->
                        update {
                            it.copy(tracks = it.tracks.copy(loading = false, error = result.error))
                        }
                    is AppResult.Success ->
                        when (val data = result.value) {
                            is AlbumCatalog ->
                                update {
                                    it.copy(
                                        albumRef = data.album,
                                        title = data.album.name,
                                        artwork = data.artwork,
                                        artists = data.artists,
                                        tracks = LoadState(data.tracks),
                                    )
                                }
                            is ArtistCatalog -> {
                                val source = data.artist.source
                                val artwork = data.artwork
                                if (
                                    source != null &&
                                        validArtistArtworkSource(source) &&
                                        artwork != null &&
                                        artwork.kind != ArtworkKind.PLACEHOLDER &&
                                        (!artwork.remoteUrl.isNullOrBlank() ||
                                            !artwork.localKey.isNullOrBlank())
                                )
                                    rememberArtistArtwork(source, artwork)
                                update {
                                    it.copy(
                                        artistRef =
                                            if (it.artistId == null) data.artist else it.artistRef,
                                        title =
                                            if (it.artistId == null) data.artist.name else it.title,
                                        artwork = data.artwork,
                                        tracks = LoadState(data.tracks),
                                        albums = LoadState(data.albums),
                                        singles = LoadState(data.singles),
                                    )
                                }
                            }
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == generation)
                    update {
                        it.copy(
                            tracks =
                                it.tracks.copy(
                                    error = AppError(ErrorCode.UNKNOWN, "catalog.load", true)
                                )
                        )
                    }
            } finally {
                if (selected == generation)
                    update { it.copy(tracks = it.tracks.copy(loading = false)) }
            }
        }
    }

    fun selectSection(section: CatalogSection) = update { it.copy(section = section) }

    fun loadMore(section: CatalogPart) {
        val s = state.value
        val cursor =
            when (section) {
                CatalogPart.TRACKS -> s.tracks.data?.next
                CatalogPart.ALBUMS -> s.albums.data?.next
                CatalogPart.SINGLES -> s.singles.data?.next
            } ?: return
        if (pages[section]?.isActive == true) return
        val selected = generation
        pageLoading(section, true, null)
        pages[section] = scope.launch {
            try {
                when (section) {
                    CatalogPart.TRACKS ->
                        when (val result = browse.moreTracks(cursor)) {
                            is AppResult.Failure ->
                                if (selected == generation)
                                    pageLoading(section, false, result.error)
                            is AppResult.Success -> {
                                ensureActive()
                                if (selected == generation)
                                    update {
                                        it.copy(
                                            tracks =
                                                LoadState(
                                                    CatalogPage(
                                                        it.tracks.data?.items.orEmpty() +
                                                            result.value.items,
                                                        result.value.next,
                                                    )
                                                )
                                        )
                                    }
                            }
                        }
                    else ->
                        when (val result = browse.moreAlbums(cursor)) {
                            is AppResult.Failure ->
                                if (selected == generation)
                                    pageLoading(section, false, result.error)
                            is AppResult.Success -> {
                                ensureActive()
                                if (selected == generation)
                                    update {
                                        if (section == CatalogPart.ALBUMS)
                                            it.copy(
                                                albums =
                                                    LoadState(
                                                        CatalogPage(
                                                            it.albums.data?.items.orEmpty() +
                                                                result.value.items,
                                                            result.value.next,
                                                        )
                                                    )
                                            )
                                        else
                                            it.copy(
                                                singles =
                                                    LoadState(
                                                        CatalogPage(
                                                            it.singles.data?.items.orEmpty() +
                                                                result.value.items,
                                                            result.value.next,
                                                        )
                                                    )
                                            )
                                    }
                            }
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == generation)
                    pageLoading(section, false, AppError(ErrorCode.UNKNOWN, "catalog.page", true))
            } finally {
                if (selected == generation)
                    pageLoading(
                        section,
                        false,
                        when (section) {
                            CatalogPart.TRACKS -> state.value.tracks.error
                            CatalogPart.ALBUMS -> state.value.albums.error
                            CatalogPart.SINGLES -> state.value.singles.error
                        },
                    )
            }
        }
    }

    fun play(index: Int) {
        val tracks =
            (if (state.value.section == CatalogSection.FAVORITES) state.value.favoriteTracks
                else state.value.allTracks())
                .toList()
        if (index !in tracks.indices) return
        val selected = generation
        val command = ++commandGeneration
        update { it.copy(commandError = null) }
        scope.launch {
            val result =
                try {
                    player.playList(tracks, index)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AppResult.Failure(AppError(ErrorCode.UNKNOWN, "catalog.play", true))
                }
            ensureActive()
            if (selected == generation && command == commandGeneration)
                update { it.copy(commandError = result.errorOrNull()) }
        }
    }

    fun retry() =
        open(state.value.copy(tracks = state.value.tracks.copy(error = null)), force = true)

    fun networkInvalidated() {
        ++generation
        request?.cancel()
        pages.values.forEach(Job::cancel)
        cache.clear()
        mutable.update {
            it.copy(
                tracks =
                    it.tracks.copy(
                        loading = false,
                        error =
                            if (it.artistId != null && it.artistRef?.source == null) null
                            else AppError(ErrorCode.NETWORK, "catalog.route", true),
                    ),
                albums = it.albums.copy(loading = false),
                singles = it.singles.copy(loading = false),
            )
        }
    }

    fun close() {
        ++generation
        job.cancel()
    }

    private fun pageLoading(part: CatalogPart, loading: Boolean, error: AppError?) = update {
        when (part) {
            CatalogPart.TRACKS -> it.copy(tracks = it.tracks.copy(loading = loading, error = error))
            CatalogPart.ALBUMS -> it.copy(albums = it.albums.copy(loading = loading, error = error))
            CatalogPart.SINGLES ->
                it.copy(singles = it.singles.copy(loading = loading, error = error))
        }
    }

    private fun favorites(s: CatalogScreenState): List<Track> {
        if (s.artistId != null) {
            val directory = currentDirectory() ?: return s.favoriteTracks
            val root = ArtistResolver.root(s.artistId, directory)
            return local.favorites.filter { track ->
                ArtistResolver.present(track, directory).artists.any { it.id == root }
            }
        }
        val source = s.albumRef?.source ?: s.artistRef?.source
        if (source != null)
            return local.favorites.filter { track ->
                if (s.albumRef != null) track.album?.source == source
                else track.artists.any { it.source == source }
            }
        val group =
            if (s.albumRef != null) local.albums.find { it.key == s.localKey }?.tracks
            else local.artists.find { it.key == s.localKey }?.tracks
        val ids = group.orEmpty().map { it.id }.toSet()
        return local.favorites.filter { it.id in ids }
    }

    private fun currentDirectory() =
        artists?.value?.data?.takeIf { it.revision == local.artistRevision }

    private fun applyLocal(s: CatalogScreenState): CatalogScreenState {
        val id = s.artistId ?: return s
        val d = currentDirectory() ?: return s
        if (id !in d.profiles) return s
        val p = ArtistResolver.profile(id, d)
        val tracks = local.artists.firstOrNull { it.key == p.id.value }?.tracks.orEmpty()
        return s.copy(
            artistId = p.id,
            artistRef = ArtistRef(p.name, p.source),
            title = p.name,
            localTracks = tracks,
            artwork = s.artwork ?: tracks.firstOrNull()?.artwork,
        )
    }

    private fun update(change: (CatalogScreenState) -> CatalogScreenState) {
        mutable.update(change)
        if (state.value.title.isNotBlank()) {
            cache[key(state.value)] = state.value
            while (cache.size > 8) cache.remove(cache.keys.first())
        }
    }
}

fun CatalogScreenState.allTracks(): List<Track> =
    (localTracks + tracks.data?.items.orEmpty()).distinctBy { it.id }
