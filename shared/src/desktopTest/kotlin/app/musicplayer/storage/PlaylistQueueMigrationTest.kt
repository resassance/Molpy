package app.musicplayer.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class PlaylistQueueMigrationTest {
    @Test
    fun schemaSixLibrarySurvives() = runBlocking {
        val directory = Files.createTempDirectory("molpy-playlist-migration-").toFile()
        val path = directory.resolve("library.db").absolutePath
        try {
            val schema =
                Json.parseToJsonElement(
                        Files.readString(
                            Path.of("schemas/app.musicplayer.storage.MusicDatabase/6.json")
                        )
                    )
                    .jsonObject["database"]!!
                    .jsonObject
            BundledSQLiteDriver().open(path).use { c ->
                schema["entities"]!!.jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    c.execSQL(
                        entity["createSql"]!!
                            .jsonPrimitive
                            .content
                            .replace("${'$'}{TABLE_NAME}", table)
                    )
                    entity["indices"]?.jsonArray.orEmpty().forEach {
                        c.execSQL(
                            it.jsonObject["createSql"]!!
                                .jsonPrimitive
                                .content
                                .replace("${'$'}{TABLE_NAME}", table)
                        )
                    }
                }
                schema["setupQueries"]!!.jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
                c.execSQL("PRAGMA user_version=6")
                c.execSQL("INSERT INTO settings VALUES ('retained','yes')")
                c.execSQL("INSERT INTO artists VALUES ('artist','Custom artist',NULL,NULL,NULL)")
                c.execSQL("INSERT INTO queue_entries VALUES ('a','track-a',0)")
                c.execSQL(
                    "INSERT INTO queue_snapshot VALUES (1,'a',4321,1,1,'{\"version\":1,\"history\":[],\"upcoming\":[],\"priority\":[]}')"
                )
            }
            val db = createMusicDatabase(path)
            try {
                val saved = db.dao().queueSnapshot()!!
                assertEquals("a", saved.currentEntryId)
                assertEquals(4321L, saved.positionMs)
                assertTrue(saved.shuffleEnabled)
                assertEquals(0L, saved.revision)
                assertNull(saved.sourceJson)
                assertEquals("yes", db.dao().setting("retained")!!.value)
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path).use { c ->
                c.prepare("PRAGMA user_version").use {
                    it.step()
                    assertEquals(7L, it.getLong(0))
                }
                c.prepare("SELECT name FROM artists WHERE id='artist'").use {
                    assertTrue(it.step())
                    assertEquals("Custom artist", it.getText(0))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
