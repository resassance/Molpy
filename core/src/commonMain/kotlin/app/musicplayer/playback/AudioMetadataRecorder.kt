package app.musicplayer.playback

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.library.AudioMetadataRepository
import app.musicplayer.model.*
import kotlinx.coroutines.*

class AudioMetadataRecorder(
    private val repository: AudioMetadataRepository,
    private val scope: CoroutineScope,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun record(candidate: PlaybackCandidate, lease: ResolvedPlayback) {
        val metadata = lease.audioMetadata ?: return
        if (lease.candidateId != candidate.id) return
        val key =
            try {
                AudioMetadataKey(candidate.id, candidate.source, lease.representation.id)
            } catch (_: IllegalArgumentException) {
                report(ErrorCode.PROVIDER_CHANGED)
                return
            }
        val value = AudioMetadata.fromSourceOffset(metadata.relativeLoudnessDb)
        scope.launch(dispatcher) {
            try {
                repository.put(key, value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                report(ErrorCode.DATABASE)
            }
        }
    }

    private fun report(code: ErrorCode) =
        diagnostics(DiagnosticEvent("audio.metadata.save", "storage", null, null, null, code, 0))
}
