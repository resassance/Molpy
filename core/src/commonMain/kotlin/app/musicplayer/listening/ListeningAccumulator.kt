package app.musicplayer.listening

import app.musicplayer.model.*

class ListeningAccumulator(private val ownerId: String) {
    private data class Session(
        val id: String,
        val attempt: Long,
        val entry: QueueEntry,
        val track: Track,
        val started: Long,
        var lastPlayed: Long,
        var played: Long = 0,
        var revision: Long = 0,
        var emittedPlayed: Long = -1,
        var emittedAt: Long = Long.MIN_VALUE,
    )

    private var current: Session? = null
    private var playing = false
    private var lastMonotonic: Long? = null
    private var lastWall = 0L
    private var newestAttempt = Long.MIN_VALUE

    fun observe(
        snapshot: PlaybackSnapshot,
        monotonicMs: Long,
        wallMs: Long,
    ): List<ListeningCheckpoint> {
        val attempt = snapshot.attemptId
        if (attempt != null && attempt < newestAttempt) return emptyList()
        if (attempt != null) newestAttempt = attempt
        val result = mutableListOf<ListeningCheckpoint>()
        settle(monotonicMs, wallMs)
        val entry = snapshot.current
        val track = snapshot.track
        val valid = attempt != null && entry != null && track != null && entry.trackId == track.id
        val same = valid && current?.let { it.attempt == attempt && it.entry == entry } == true
        if (!same) {
            emit()?.let(result::add)
            current = null
            if (valid && snapshot.status == PlayerStatus.PLAYING) {
                current = Session("$ownerId:$attempt", attempt, entry, track, wallMs, wallMs)
                emit()?.let(result::add)
            }
        }
        val wasPlaying = playing
        playing = valid && snapshot.status == PlayerStatus.PLAYING && current != null
        if (wasPlaying && !playing) emit()?.let(result::add)
        lastWall = wallMs
        return result
    }

    fun checkpoint(monotonicMs: Long): List<ListeningCheckpoint> {
        settle(monotonicMs, null)
        return listOfNotNull(emit())
    }

    private fun settle(monotonic: Long, wall: Long?) {
        val previous = lastMonotonic
        if (previous != null && playing && monotonic >= previous) {
            val delta = monotonic - previous
            current?.let { session ->
                if (delta <= Long.MAX_VALUE - session.played) session.played += delta
                val elapsedWall =
                    if (lastWall <= Long.MAX_VALUE - delta) lastWall + delta else Long.MAX_VALUE
                session.lastPlayed = maxOf(session.lastPlayed, wall ?: elapsedWall)
                lastWall = wall ?: elapsedWall
            }
        }
        lastMonotonic = monotonic
    }

    private fun emit(): ListeningCheckpoint? {
        val session = current ?: return null
        if (session.played == session.emittedPlayed && session.lastPlayed == session.emittedAt)
            return null
        session.emittedPlayed = session.played
        session.emittedAt = session.lastPlayed
        return ListeningCheckpoint(
            session.id,
            ++session.revision,
            session.entry.id,
            session.track,
            session.started,
            session.lastPlayed,
            session.played,
        )
    }
}
