package app.musicplayer.downloads

import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.library.LibraryRepository
import app.musicplayer.lyrics.LyricsLoader
import app.musicplayer.model.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ancillary work has its own lifetime and availability; it never publishes audio availability. */
internal class OfflineResources(
    internal val directory: File,
    private val repository: OfflineRepository,
    private val library: LibraryRepository,
    private val artwork: ArtworkLoader,
    private val lyrics: LyricsLoader,
    scope: CoroutineScope,
    private val canFetch: () -> Boolean,
    private val withAssetLock: suspend (suspend () -> Unit) -> Unit = { it() },
    private val retainedArtworkKeys: suspend () -> Set<String> = { emptySet() },
) {
    private val mutex = Mutex()
    private val changed = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (ignored in changed) {
                try {
                    reconcile()
                    repository
                        .listAssets()
                        .filter { it.state == DownloadState.AVAILABLE }
                        .forEach { asset ->
                            val candidate =
                                asset.trackSnapshot.candidates.single { it.id == asset.candidateId }
                            save(asset.trackSnapshot, candidate, asset.id)
                        }
                    reconcile()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    /* Ancillary failure is independent of verified audio. */
                }
            }
        }
    }

    fun signal() {
        changed.trySend(Unit)
    }

    suspend fun save(track: Track, candidate: PlaybackCandidate, assetId: AssetId): Unit =
        mutex.withLock {
            val asset = repository.findAsset(assetId) ?: return@withLock
            if (
                asset.trackSnapshot != track ||
                    asset.candidateId != candidate.id ||
                    asset.trackSnapshot.candidates.single { it.id == candidate.id } != candidate ||
                    asset.state != DownloadState.AVAILABLE
            )
                return@withLock
            // Preserve the downloader's immutable metadata. Only its explicit local artwork link
            // changes.
            if (
                asset.trackSnapshot.artwork.localKey?.let(::ownedFile)?.isFile != true && canFetch()
            ) {
                ancillary {
                    val fetched = artwork.fetch(track.artwork).valueOrNull() ?: return@ancillary
                    val key =
                        MessageDigest.getInstance("SHA-256").digest(fetched.bytes).joinToString(
                            ""
                        ) {
                            "%02x".format(it)
                        }
                    directory.mkdirs()
                    val file = File(directory, "$key.artwork")
                    if (!file.isFile) {
                        val temporary = File.createTempFile("artwork-", ".partial", directory)
                        try {
                            temporary.writeBytes(fetched.bytes)
                            check(temporary.renameTo(file))
                        } finally {
                            temporary.delete()
                        }
                    }
                    // A removal or transition during the fetch must not resurrect a mapping.
                    withAssetLock {
                        val current = repository.findAsset(assetId)
                        if (
                            current != null &&
                                current.state == DownloadState.AVAILABLE &&
                                current.trackId == asset.trackId &&
                                current.candidateId == asset.candidateId &&
                                current.representation == asset.representation
                        ) {
                            repository.saveMapping(
                                current.copy(
                                    trackSnapshot =
                                        current.trackSnapshot.copy(
                                            artwork =
                                                current.trackSnapshot.artwork.copy(
                                                    localKey = file.absolutePath
                                                )
                                        )
                                )
                            )
                        }
                    }
                }
            }
            // LyricsLoader caches strictly by Track/candidate and bounds provider time; failed
            // fetches are not cached.
            if (canFetch() && repository.findAsset(assetId)?.candidateId == candidate.id)
                ancillary { lyrics.load(track, candidate) }
        }

    /**
     * Reconcile resources against live references, including library snapshots that use local
     * artwork.
     */
    suspend fun reconcile(): Unit = mutex.withLock {
        withAssetLock {
            for (asset in repository.listAssets()) {
                val key = asset.trackSnapshot.artwork.localKey ?: continue
                if (ownedFile(key)?.isFile != true)
                    repository.saveMapping(
                        asset.copy(
                            trackSnapshot =
                                asset.trackSnapshot.copy(
                                    artwork = asset.trackSnapshot.artwork.copy(localKey = null)
                                )
                        )
                    )
            }
            val references = referencedArtwork()
            directory
                .listFiles()
                ?.filter { it.isFile && it.absolutePath !in references }
                ?.forEach { it.delete() }
        }
    }

    internal suspend fun referencedArtwork(): Set<String> {
        val tracks =
            repository.listAssets().map { it.trackSnapshot } +
                library.favorites.first() +
                library.recent.first() +
                library.restoreQueue().entries.mapNotNull { library.getTrack(it.trackId) }
        return (tracks.mapNotNull { it.artwork.localKey } + retainedArtworkKeys())
            .mapNotNull { ownedFile(it)?.absolutePath }
            .toSet()
    }

    internal fun ownedFile(key: String): File? {
        val file = File(key)
        return file.takeIf {
            it.parentFile?.absoluteFile == directory.absoluteFile &&
                it.name.matches(Regex("[a-f0-9]{64}\\.artwork"))
        }
    }

    private suspend fun ancillary(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            /* Absence is an ancillary resource state, never an audio failure. */
        }
    }
}
