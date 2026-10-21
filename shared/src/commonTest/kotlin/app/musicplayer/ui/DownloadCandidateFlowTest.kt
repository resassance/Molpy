package app.musicplayer.ui

import app.musicplayer.downloads.*
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.TrackActions
import app.musicplayer.ui.components.selectDownload
import app.musicplayer.ui.downloads.DownloadsModel
import app.musicplayer.ui.player.PlayerModel
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadCandidateFlowTest {
    @Test
    fun savedNonFirstCandidateDrivesLyricsAndExplicitDownloadWhileProjectionStaysNormalized() =
        runTest {
            val first =
                PlaybackCandidate(
                    CandidateId("first"),
                    SourceRef("fixture", "audio", "first"),
                    "other",
                    1000,
                    MatchConfidence.HIGH,
                )
            val recorded =
                first.copy(
                    id = CandidateId("recorded"),
                    source = SourceRef("fixture", "audio", "recorded"),
                    version = "saved",
                )
            val track =
                Track(
                    TrackId("track"),
                    "Saved snapshot",
                    emptyList(),
                    null,
                    Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    1000,
                    setOf(recorded.source),
                    MetadataProvenance(recorded.source, 0, false),
                    listOf(first, recorded),
                )
            val asset =
                OfflineAsset(
                    AssetId("asset"),
                    track.id,
                    track,
                    recorded.id,
                    recorded.source,
                    Representation("wav", "audio/wav", "pcm", 100, 1000, null),
                    "key",
                    40,
                    DownloadState.DOWNLOADING,
                )
            val failure = AppError(ErrorCode.STORAGE_FULL, "download.transfer", true)
            val downloads =
                object : DownloadPort {
                    override val states = MutableStateFlow(listOf(asset))
                    override val failures = MutableStateFlow(mapOf(asset.id to failure))
                    var candidate: CandidateId? = null
                    var removed = false

                    override suspend fun enqueue(
                        track: Track,
                        candidate: PlaybackCandidate,
                    ): AppResult<AssetId> {
                        this.candidate = candidate.id
                        return AppResult.Success(asset.id)
                    }

                    override suspend fun pause(id: AssetId) = AppResult.Success(Unit)

                    override suspend fun resume(id: AssetId) = AppResult.Failure(failure)

                    override suspend fun cancel(id: AssetId) = AppResult.Success(Unit)

                    override suspend fun remove(id: AssetId): AppResult<Unit> {
                        removed = true
                        states.value = emptyList()
                        return AppResult.Success(Unit)
                    }

                    override suspend fun removeAll() = AppResult.Success(Unit)
                }
            val player =
                EngineeringPort().apply {
                    snapshot.value =
                        PlaybackSnapshot(
                            PlayerStatus.PLAYING,
                            QueueEntry(QueueEntryId("entry"), track.id),
                            track,
                            candidateId = recorded.id,
                        )
                }
            val library = EngineeringLibrary()
            var lyricsCandidate: CandidateId? = null
            val lyrics =
                LyricsLoader(
                    listOf(
                        object : LyricsPort {
                            override suspend fun fetch(
                                track: Track,
                                candidate: PlaybackCandidate,
                            ): AppResult<LyricsCandidate?> {
                                lyricsCandidate = candidate.id
                                return AppResult.Success(null)
                            }
                        }
                    ),
                    LyricsSelector(),
                    SessionLyricsCache(),
                ) {
                    0L
                }
            val model = PlayerModel(player, library, lyrics, backgroundScope)
            val view =
                DownloadsModel(
                    downloads,
                    { StorageUsageSnapshot(40, mapOf(asset.id to 40)) },
                    backgroundScope,
                )
            val actions = TrackActions(library, MutableCollections(), player, downloads)
            runCurrent()
            assertEquals(listOf(asset), view.state.value.assets)
            assertEquals(failure, view.state.value.failures[asset.id])
            assertEquals(40L, view.state.value.usage.totalBytes)
            val selected = assertNotNull(selectDownload(track, player.snapshot.value))
            actions.download(selected.track, selected.candidate)
            runCurrent()
            assertEquals(recorded.id, lyricsCandidate)
            assertEquals(recorded.id, downloads.candidate)
            assertEquals(track, model.state.value.currentTrack)
            view.resume(asset.id)
            runCurrent()
            assertEquals(ErrorCode.STORAGE_FULL, view.state.value.failures[asset.id]?.code)
            view.remove(asset.id)
            runCurrent()
            assertTrue(downloads.removed)
            assertTrue(view.state.value.assets.isEmpty())
            model.close()
            view.close()
        }
}
