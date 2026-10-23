package app.musicplayer.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.musicplayer.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class QueueMigrationTest {
    @Test
    fun schemaFiveQueueAndArtistDataSurviveWithoutInventedHistory() = runBlocking {
        val directory = Files.createTempDirectory("molpy-queue-migration-").toFile()
        val path = directory.resolve("library.db").absolutePath
        try {
            val schema =
                Json.parseToJsonElement(
                        Files.readString(
                            Path.of("schemas/app.musicplayer.storage.MusicDatabase/5.json")
                        )
                    )
                    .jsonObject["database"]!!
                    .jsonObject
            BundledSQLiteDriver().open(path).use { connection ->
                schema["entities"]!!.jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
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
                connection.execSQL("PRAGMA user_version=5")
                connection.execSQL(
                    "INSERT INTO queue_entries VALUES ('a','missing-a',0),('b','missing-b',1),('c','missing-c',2)"
                )
                connection.execSQL("INSERT INTO queue_snapshot VALUES (1,'b',4321,1)")
                connection.execSQL(
                    "INSERT INTO artists VALUES ('artist','Custom artist',NULL,NULL,NULL)"
                )
                connection.execSQL(
                    "INSERT INTO settings VALUES ('queue-migration-marker','retained')"
                )
            }
            val db = createMusicDatabase(path)
            try {
                val saved = db.dao().queueSnapshot()!!
                assertFalse(saved.shuffleEnabled)
                assertNull(saved.traversalJson)
                val entries =
                    db.dao().queueEntries().map {
                        QueueEntry(QueueEntryId(it.entryId), TrackId(it.trackId))
                    }
                val q = QueueRecordMapper.restore(entries, saved)
                assertEquals(QueueEntryId("b"), q.currentEntryId)
                assertEquals(4321L, q.positionMs)
                assertEquals(listOf(QueueEntryId("c")), q.traversal!!.upcoming)
                assertTrue(q.traversal!!.history.isEmpty())
                assertFalse(q.playWhenReady)
                assertEquals("retained", db.dao().setting("queue-migration-marker")?.value)
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("PRAGMA user_version").use {
                    it.step()
                    assertEquals(7L, it.getLong(0))
                }
                connection.prepare("SELECT name FROM artists WHERE id='artist'").use {
                    assertTrue(it.step())
                    assertEquals("Custom artist", it.getText(0))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
