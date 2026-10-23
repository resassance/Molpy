package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.listening.ArtistListening
import app.musicplayer.model.ArtistRef

internal object ArtistListeningProjection {
    fun aggregate(
        sessions: List<ArtistSessionSnapshot>,
        directory: ArtistDirectory,
    ): List<ArtistListening> {
        val totals = linkedMapOf<ArtistId, Pair<Long, Long>>()
        for (session in sessions) {
            val played = session.playedMs ?: continue
            require(played >= 0)
            val artists =
                (directory.overrides[session.trackId] ?: session.artists)
                    .map { ArtistResolver.root(it, directory) }
                    .distinct()
            for (id in artists) {
                val old = totals[id]
                totals[id] =
                    addTime(old?.first ?: 0, played) to
                        maxOf(old?.second ?: Long.MIN_VALUE, session.lastPlayedAtMs)
            }
        }
        return totals
            .map { (id, time) ->
                val p = ArtistResolver.profile(id, directory)
                ArtistListening(id.value, ArtistRef(p.name, p.source), time.first, time.second)
            }
            .sortedWith(
                compareByDescending<ArtistListening> { it.playedMs }
                    .thenByDescending { it.lastPlayedAtMs }
                    .thenBy { it.key }
            )
    }

    fun addTime(a: Long, b: Long): Long {
        require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b)
        return a + b
    }
}
