package app.musicplayer.downloads

import app.musicplayer.model.CandidateId
import app.musicplayer.model.TrackId

class DownloadPolicy {
    fun mayStart(activeCount: Int): Boolean = activeCount == 0

    /** Injective UTF-8 encoding avoids delimiter collisions and contains no temporary URL. */
    fun keyFor(trackId: TrackId, candidateId: CandidateId, representationId: String): String {
        require(representationId.isNotBlank()) { "Representation ID must not be blank" }
        return listOf(trackId.value, candidateId.value, representationId).joinToString("/", "offline-v1/") { value ->
            value.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
    }

    fun mayPublishAvailable(transportComplete: Boolean, verified: Boolean): Boolean = transportComplete && verified
}
