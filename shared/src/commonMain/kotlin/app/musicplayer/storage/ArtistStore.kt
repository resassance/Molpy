package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlin.uuid.Uuid

internal class ArtistStore(private val database: MusicDatabase) {
    private val dao = database.artistDao()

    suspend fun snapshot(): ArtistDirectory =
        ArtistDirectory(
            revision = database.dao().setting("artists.revision")?.value?.toLong() ?: 0,
            profiles = dao.profiles().associate { ArtistId(it.id) to it.domain() },
            aliases = dao.aliases().associate { ArtistAliasKey(it.key) to ArtistId(it.artistId) },
            overrides =
                dao.overrides()
                    .groupBy { TrackId(it.trackId) }
                    .mapValues { (_, rows) -> rows.map { ArtistId(it.artistId) } },
            merges = dao.merges().associate { ArtistId(it.originId) to it.domain() },
        )

    suspend fun ensureRaw(track: Track): TrackPresentation {
        registerRaw(track)
        return ArtistResolver.present(track, snapshot())
    }

    suspend fun registerRaw(track: Track) {
        for ((i, artist) in track.artists.withIndex()) if (artist.name.isNotBlank())
            register(ArtistResolver.alias(track, i), artist.name, artist.source)
    }

    suspend fun register(key: ArtistAliasKey, name: String, source: SourceRef?): ArtistId {
        val existing = dao.alias(key.value)
        if (existing != null) {
            val profile = requireNotNull(dao.profile(existing.artistId))
            if (profile.name != name.trim()) {
                dao.putProfile(profile.copy(name = name.trim()))
                advanceRevision()
            }
            return ArtistId(profile.id)
        }
        val id = create(name, source)
        dao.insertAlias(ArtistAliasRecord(key.value, id.value))
        return id
    }

    suspend fun create(name: String, source: SourceRef?): ArtistId {
        require(name.isNotBlank())
        val id = ArtistId(Uuid.random().toString())
        dao.putProfile(
            ArtistProfileRecord(id.value, name.trim(), source?.provider, source?.kind, source?.id)
        )
        advanceRevision()
        return id
    }

    suspend fun advanceRevision(): Long {
        val old = database.dao().setting("artists.revision")?.value?.toLong() ?: 0
        require(old < Long.MAX_VALUE)
        database.dao().putSetting(SettingsRecord("artists.revision", (old + 1).toString()))
        return old + 1
    }

    suspend fun sessions(): List<ArtistSessionSnapshot> {
        val credits = dao.credits().groupBy { it.sessionId }
        return database.listeningDao().allSessions().map {
            ArtistSessionSnapshot(
                it.id,
                TrackId(it.trackId),
                credits[it.id].orEmpty().map { credit -> ArtistId(credit.artistId) },
                it.playedMs,
                it.lastPlayedAtMs,
            )
        }
    }

    suspend fun rebuildListening() {
        val totals = ArtistListeningProjection.aggregate(sessions(), snapshot())
        dao.clearListening()
        for (total in totals) database
            .listeningDao()
            .putArtist(
                ArtistListeningRecord(
                    total.key,
                    total.artist.name,
                    total.artist.source?.provider,
                    total.artist.source?.kind,
                    total.artist.source?.id,
                    total.playedMs,
                    total.lastPlayedAtMs,
                )
            )
    }
}
