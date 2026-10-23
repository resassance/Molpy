package app.musicplayer.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.musicplayer.downloads.*
import app.musicplayer.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class AudioMetadataMigrationTest {
    @Test
    fun schemaThreePreservesLibraryQueueDownloadsAndListening() = runBlocking {
        val directory = Files.createTempDirectory("molpy-audio-migration-").toFile()
        val path = directory.resolve("library.db").absolutePath
        val track = fixture("legacy-audio")
        val candidate = track.candidates.single()
        val asset =
            OfflineAsset(
                AssetId("saved"),
                track.id,
                track,
                candidate.id,
                candidate.source,
                Representation("140", "audio/mp4", "aac", 1000, 120000, null),
                "saved-audio",
                1000,
                DownloadState.AVAILABLE,
            )
        try {
            val schema =
                Json.parseToJsonElement(
                        Files.readString(
                            Path.of("schemas/app.musicplayer.storage.MusicDatabase/3.json")
                        )
                    )
                    .jsonObject["database"]!!
                    .jsonObject
            BundledSQLiteDriver().open(path).use { connection ->
                schema["entities"]!!.jsonArray.forEach { item ->
                    val entity = item.jsonObject
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    connection.execSQL(
                        entity["createSql"]!!
                            .jsonPrimitive
                            .content
                            .replace("${'$'}{TABLE_NAME}", table)
                    )
                    entity["indices"]?.jsonArray.orEmpty().forEach {
                        connection.execSQL(
                            it.jsonObject["createSql"]!!
                                .jsonPrimitive
                                .content
                                .replace("${'$'}{TABLE_NAME}", table)
                        )
                    }
                }
                schema["setupQueries"]!!.jsonArray.forEach {
                    connection.execSQL(it.jsonPrimitive.content)
                }
                connection.execSQL("PRAGMA user_version = 3")
                val t = RecordMapper.track(track, track.id, 0)
                connection.insert(
                    "tracks",
                    listOf(
                        t.id,
                        t.recordVersion,
                        t.title,
                        t.artistsJson,
                        t.albumJson,
                        t.artworkKind,
                        t.artworkUrl,
                        t.artworkLocalKey,
                        t.durationMs,
                        t.provenanceProvider,
                        t.provenanceKind,
                        t.provenanceId,
                        t.fetchedAtMs,
                        t.tentative,
                        t.cachedAtMs,
                    ),
                )
                track.externalIds.forEach {
                    connection.insert(
                        "external_identity",
                        listOf(it.provider, it.kind, it.id, track.id.value),
                    )
                }
                RecordMapper.candidates(track, track.id).forEach {
                    connection.insert(
                        "candidates",
                        listOf(
                            it.trackId,
                            it.candidateId,
                            it.position,
                            it.provider,
                            it.kind,
                            it.sourceId,
                            it.version,
                            it.durationMs,
                            it.confidence,
                        ),
                    )
                }
                connection.insert("favorites", listOf(track.id.value, 5))
                connection.insert("recent_history", listOf(track.id.value, 6))
                connection.insert("queue_entries", listOf("entry", track.id.value, 0))
                connection.insert("queue_snapshot", listOf(1, "entry", 4321, true))
                connection.insert("playlists", listOf("playlist", "Saved songs", 5))
                connection.insert("playlist_items", listOf("item", "playlist", track.id.value, 0))
                connection.insert(
                    "listening_sessions",
                    listOf(
                        "session",
                        1,
                        "entry",
                        track.id.value,
                        1,
                        9001,
                        9000,
                        creditsJson(track),
                    ),
                )
                connection.insert("track_listening", listOf(track.id.value, 9000, 10))
                track.artists.forEachIndexed { index, artist ->
                    connection.insert(
                        "artist_listening",
                        listOf(
                            app.musicplayer.collections.CollectionGrouping.artistKey(track, index),
                            artist.name,
                            artist.source?.provider,
                            artist.source?.kind,
                            artist.source?.id,
                            9000,
                            9001,
                        ),
                    )
                }
                connection.insert("settings", listOf("existing", "keep"))
                val o = OfflineRecordMapper.record(asset)
                connection.insert(
                    "offline_assets",
                    listOf(
                        o.id,
                        o.trackId,
                        o.snapshotJson,
                        o.candidateId,
                        o.provider,
                        o.kind,
                        o.sourceId,
                        o.representationId,
                        o.mimeType,
                        o.codec,
                        o.expectedBytes,
                        o.expectedDurationMs,
                        o.validator,
                        o.storageKey,
                        o.bytes,
                        o.state,
                    ),
                )
            }
            val db = createMusicDatabase(path)
            try {
                val library = RoomLibraryRepository(db) { 0 }
                assertEquals(listOf(track), library.favorites.first())
                assertEquals(listOf(track), library.recent.first())
                assertEquals(asset, library.findAvailable(track.id))
                assertEquals(4321L, library.restoreQueue().positionMs)
                assertEquals("keep", db.dao().setting("existing")?.value)
                val metadata = RoomAudioMetadataRepository(db)
                val key = AudioMetadataKey(candidate.id, candidate.source, "140")
                assertNull(metadata.get(key))
                metadata.put(key, AudioMetadata(6.0))
                assertEquals(AudioMetadata(6.0), metadata.get(key))
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path).use { connection ->
                for (sql in
                    listOf(
                        "SELECT COUNT(*) FROM playlists",
                        "SELECT COUNT(*) FROM playlist_items",
                    )) connection.prepare(sql).use {
                    assertTrue(it.step())
                    assertEquals(1L, it.getLong(0))
                }
                for (table in
                    listOf("track_listening", "artist_listening", "listening_sessions")) connection
                    .prepare("SELECT playedMs FROM $table")
                    .use {
                        assertTrue(it.step())
                        assertEquals(9000L, it.getLong(0))
                    }
                connection.prepare("PRAGMA user_version").use {
                    assertTrue(it.step())
                    assertEquals(5L, it.getLong(0))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun creditsJson(track: Track) = buildJsonArray {
        track.artists.forEachIndexed { index, artist ->
            add(
                buildJsonObject {
                    put(
                        "key",
                        app.musicplayer.collections.CollectionGrouping.artistKey(track, index),
                    )
                    put("name", artist.name)
                    put("provider", artist.source?.provider?.let(::JsonPrimitive) ?: JsonNull)
                    put("kind", artist.source?.kind?.let(::JsonPrimitive) ?: JsonNull)
                    put("sourceId", artist.source?.id?.let(::JsonPrimitive) ?: JsonNull)
                }
            )
        }
    }
        .toString()

    private fun SQLiteConnection.insert(table: String, values: List<Any?>) {
        prepare("INSERT INTO $table VALUES (${values.joinToString { "?" }})").use { statement ->
            values.forEachIndexed { index, value ->
                when (value) {
                    null -> statement.bindNull(index + 1)
                    is String -> statement.bindText(index + 1, value)
                    is Boolean -> statement.bindLong(index + 1, if (value) 1 else 0)
                    is Number -> statement.bindLong(index + 1, value.toLong())
                    else -> error("Unsupported fixture value")
                }
            }
            statement.step()
        }
    }
}
