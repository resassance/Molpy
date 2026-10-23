package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*

object Migration4To5 : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        val schema =
            listOf(
                "CREATE TABLE IF NOT EXISTS artists (id TEXT NOT NULL, name TEXT NOT NULL, provider TEXT, kind TEXT, sourceId TEXT, PRIMARY KEY(id))",
                "CREATE TABLE IF NOT EXISTS artist_aliases (`key` TEXT NOT NULL, artistId TEXT NOT NULL, PRIMARY KEY(`key`), FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                "CREATE INDEX IF NOT EXISTS index_artist_aliases_artistId ON artist_aliases(artistId)",
                "CREATE TABLE IF NOT EXISTS track_artist_overrides (trackId TEXT NOT NULL, position INTEGER NOT NULL, artistId TEXT NOT NULL, PRIMARY KEY(trackId,position), FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                "CREATE INDEX IF NOT EXISTS index_track_artist_overrides_artistId ON track_artist_overrides(artistId)",
                "CREATE UNIQUE INDEX IF NOT EXISTS index_track_artist_overrides_trackId_artistId ON track_artist_overrides(trackId,artistId)",
                "CREATE TABLE IF NOT EXISTS artist_merges (originId TEXT NOT NULL, targetId TEXT NOT NULL, preferredProvider TEXT, preferredKind TEXT, preferredSourceId TEXT, revision INTEGER NOT NULL, PRIMARY KEY(originId), FOREIGN KEY(originId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(targetId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                "CREATE INDEX IF NOT EXISTS index_artist_merges_targetId ON artist_merges(targetId)",
                "CREATE TABLE IF NOT EXISTS listening_artist_credits (sessionId TEXT NOT NULL, artistId TEXT NOT NULL, PRIMARY KEY(sessionId,artistId), FOREIGN KEY(sessionId) REFERENCES listening_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(artistId) REFERENCES artists(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                "CREATE INDEX IF NOT EXISTS index_listening_artist_credits_artistId ON listening_artist_credits(artistId)",
            )
        schema.forEach(connection::execSQL)
        ArtistMigrationImport(connection).run()
    }
}

private class ArtistMigrationImport(private val connection: SQLiteConnection) {
    private val profiles = linkedMapOf<ArtistId, ArtistProfile>()
    private val aliases = linkedMapOf<ArtistAliasKey, ArtistId>()

    fun run() {
        connection.prepare("SELECT id,artistsJson FROM tracks ORDER BY id").use { rows ->
            while (rows.step()) {
                val id = TrackId(rows.getText(0))
                val credits = Json.parseToJsonElement(rows.getText(1)).jsonArray
                for ((index, element) in credits.withIndex()) {
                    val credit = element.jsonObject
                    val name = credit.getValue("name").jsonPrimitive.content
                    val source =
                        credit["source"]
                            ?.takeUnless { it == JsonNull }
                            ?.jsonObject
                            ?.let {
                                SourceRef(
                                    it.getValue("provider").jsonPrimitive.content,
                                    it.getValue("kind").jsonPrimitive.content,
                                    it.getValue("id").jsonPrimitive.content,
                                )
                            }
                    if (name.isNotBlank()) register(key(id, index, name, source), name, source)
                }
            }
        }
        val sessions = mutableListOf<ArtistSessionSnapshot>()
        connection
            .prepare(
                "SELECT id,trackId,creditsJson,playedMs,lastPlayedAtMs FROM listening_sessions ORDER BY id"
            )
            .use { rows ->
                while (rows.step()) {
                    val sessionId = rows.getText(0)
                    val trackId = TrackId(rows.getText(1))
                    val ids =
                        Json.parseToJsonElement(rows.getText(2))
                            .jsonArray
                            .mapNotNull { element ->
                                val credit = element.jsonObject
                                val name = credit.getValue("name").jsonPrimitive.content
                                val source =
                                    artistSource(
                                        credit["provider"]?.jsonPrimitive?.contentOrNull,
                                        credit["kind"]?.jsonPrimitive?.contentOrNull,
                                        credit["sourceId"]?.jsonPrimitive?.contentOrNull,
                                    )
                                if (name.isBlank()) return@mapNotNull null
                                val oldKey = credit.getValue("key").jsonPrimitive.content
                                val index =
                                    if (source != null) 0
                                    else {
                                        require(
                                            oldKey.startsWith(
                                                "artist/local/${part(trackId.value)}/"
                                            )
                                        ) {
                                            "Invalid legacy artist credit"
                                        }
                                        requireNotNull(oldKey.substringAfterLast('/').toIntOrNull())
                                    }
                                register(key(trackId, index, name, source), name, source)
                            }
                            .distinct()
                    ids.forEach {
                        execute(
                            "INSERT INTO listening_artist_credits (sessionId,artistId) VALUES (?,?)",
                            sessionId,
                            it.value,
                        )
                    }
                    sessions +=
                        ArtistSessionSnapshot(
                            sessionId,
                            trackId,
                            ids,
                            if (rows.isNull(3)) null else rows.getLong(3),
                            rows.getLong(4),
                        )
                }
            }
        val totals =
            ArtistListeningProjection.aggregate(
                sessions,
                ArtistDirectory(profiles = profiles, aliases = aliases),
            )
        val oldTotal =
            connection.prepare("SELECT playedMs FROM artist_listening").use { rows ->
                var total = 0L
                while (rows.step()) total =
                    ArtistListeningProjection.addTime(total, rows.getLong(0))
                total
            }
        val newTotal = totals.fold(0L) { a, b -> ArtistListeningProjection.addTime(a, b.playedMs) }
        require(oldTotal == newTotal) { "Artist time does not match original session credits" }
        execute("DELETE FROM artist_listening")
        for (total in totals) execute(
            "INSERT INTO artist_listening (`key`,name,provider,kind,sourceId,playedMs,lastPlayedAtMs) VALUES (?,?,?,?,?,?,?)",
            total.key,
            total.artist.name,
            total.artist.source?.provider,
            total.artist.source?.kind,
            total.artist.source?.id,
            total.playedMs,
            total.lastPlayedAtMs,
        )
        execute(
            "INSERT OR REPLACE INTO settings (`key`,value) VALUES (?,?)",
            "artists.revision",
            "1",
        )
    }

    private fun key(track: TrackId, index: Int, name: String, source: SourceRef?) =
        ArtistAliasKey(
            source?.let(ArtistResolver::sourceKey)
                ?: "credit/${part(track.value)}/$index/${part(ArtistResolver.normalizedName(name))}"
        )

    private fun register(key: ArtistAliasKey, name: String, source: SourceRef?): ArtistId {
        aliases[key]?.let {
            return it
        }
        val id = ArtistId(Uuid.random().toString())
        profiles[id] = ArtistProfile(id, name.trim(), source)
        aliases[key] = id
        execute(
            "INSERT INTO artists (id,name,provider,kind,sourceId) VALUES (?,?,?,?,?)",
            id.value,
            name.trim(),
            source?.provider,
            source?.kind,
            source?.id,
        )
        execute("INSERT INTO artist_aliases (`key`,artistId) VALUES (?,?)", key.value, id.value)
        return id
    }

    private fun execute(sql: String, vararg values: Any?) {
        connection.prepare(sql).use { statement ->
            for ((index, value) in values.withIndex()) when (value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                else -> error("Unsupported migration value")
            }
            statement.step()
        }
    }

    private fun part(value: String) = "${value.length}:$value"
}
