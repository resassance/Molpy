package app.musicplayer.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.musicplayer.downloads.*
import app.musicplayer.listening.*
import app.musicplayer.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class CollectionMigrationTest {
    @Test
    fun schemaTwoPreservesUserDataAndImportsHistoryWithoutInventingTime() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("molpy-collections-migration-").toFile()
            val path = directory.resolve("library.db").absolutePath
            val track = fixture("legacy-collections")
            val config =
                NetworkConfig(
                    NetworkMode.HTTP_CONNECT,
                    ProxyEndpoint("proxy.example", 3128),
                    SecretId("ref"),
                )
            val lyrics = LyricsLookup.Instrumental(SourceRef("lyrics", "entry", "legacy"))
            val asset =
                OfflineAsset(
                    AssetId("saved"),
                    track.id,
                    track,
                    track.candidates.single().id,
                    track.candidates.single().source,
                    Representation("format", "audio/test", "test", 10, 120000, null),
                    "saved-audio",
                    10,
                    DownloadState.AVAILABLE,
                )
            try {
                val schema =
                    Json.parseToJsonElement(
                            Files.readString(
                                Path.of("schemas/app.musicplayer.storage.MusicDatabase/2.json")
                            )
                        )
                        .jsonObject["database"]!!
                        .jsonObject
                BundledSQLiteDriver().open(path).use { connection ->
                    schema["entities"]!!.jsonArray.forEach { entry ->
                        val entity = entry.jsonObject
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
                    connection.execSQL("PRAGMA user_version = 2")
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
                    connection.insert("queue_entries", listOf("one", track.id.value, 0))
                    connection.insert("queue_entries", listOf("two", track.id.value, 1))
                    connection.insert("queue_snapshot", listOf(1, "two", 4321, true))
                    connection.insert("settings", listOf("network", RecordMapper.network(config)))
                    val l = RecordMapper.lyrics(track.id, track.candidates.single().id, lyrics, 0)
                    connection.insert(
                        "lyrics_cache",
                        listOf(
                            l.trackId,
                            l.candidateId,
                            l.recordVersion,
                            l.kind,
                            l.payloadJson,
                            l.quality,
                            l.cachedAtMs,
                        ),
                    )
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
                    val repository = RoomLibraryRepository(db) { 0 }
                    assertEquals(listOf(track), repository.favorites.first())
                    assertEquals(track, repository.getTrack(track.id))
                    assertEquals(asset, repository.findAvailable(track.id))
                    assertEquals(config, repository.getNetworkConfig())
                    assertEquals(
                        lyrics,
                        repository.getLyrics(
                            track.id,
                            track.candidates.single().id,
                            Long.MAX_VALUE,
                        ),
                    )
                    val restored = repository.restoreQueue()
                    assertEquals(listOf("one", "two"), restored.entries.map { it.id.value })
                    assertEquals(4321L, restored.positionMs)
                    assertFalse(restored.playWhenReady)
                    val listening = RoomListeningRepository(db) { 0 }
                    assertEquals(
                        0L,
                        assertIs<AppResult.Success<ListeningProfile>>(listening.profile.first())
                            .value
                            .totalMs,
                    )
                    assertNull(
                        assertIs<AppResult.Success<HistoryPage>>(listening.history())
                            .value
                            .items
                            .single()
                            .playedMs
                    )
                } finally {
                    db.close()
                }
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.prepare("PRAGMA user_version").use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals(8L, stmt.getLong(0))
                    }
                    connection
                        .prepare(
                            "SELECT id, trackId, startedAtMs, playedMs FROM listening_sessions"
                        )
                        .use { stmt ->
                            assertTrue(stmt.step())
                            assertEquals("legacy/${track.id.value}/6", stmt.getText(0))
                            assertEquals(track.id.value, stmt.getText(1))
                            assertEquals(6L, stmt.getLong(2))
                            assertTrue(stmt.isNull(3))
                            assertFalse(stmt.step())
                        }
                    connection.prepare("SELECT COUNT(*) FROM track_listening").use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals(0L, stmt.getLong(0))
                    }
                }
                val reopened = createMusicDatabase(path)
                try {
                    assertEquals(
                        listOf(track),
                        RoomLibraryRepository(reopened) { 0 }.favorites.first(),
                    )
                } finally {
                    reopened.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun SQLiteConnection.insert(table: String, values: List<Any?>) {
        prepare("INSERT INTO $table VALUES (${values.joinToString { "?" }})").use { stmt ->
            values.forEachIndexed { index, value ->
                when (value) {
                    null -> stmt.bindNull(index + 1)
                    is String -> stmt.bindText(index + 1, value)
                    is Boolean -> stmt.bindLong(index + 1, if (value) 1 else 0)
                    is Number -> stmt.bindLong(index + 1, value.toLong())
                    else -> error("Unsupported fixture value")
                }
            }
            stmt.step()
        }
    }
}
