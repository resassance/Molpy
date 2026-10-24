package app.musicplayer.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class AppearanceArtworkMigrationTest {
    @Test
    fun migrationSevenToEightPreservesLibraryAndSetsClassic() = runBlocking {
        val directory = Files.createTempDirectory("molpy-artwork-migration-").toFile()
        val path = directory.resolve("library.db").absolutePath
        try {
            val schema = createLegacy(path, 7)
            val tables = schema.getValue("entities").jsonArray.map { it.jsonObject }
            val before =
                BundledSQLiteDriver().open(path).use { c ->
                    tables.forEach { entity ->
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        val fields = entity.getValue("fields").jsonArray.map { it.jsonObject }
                        val values = fields.joinToString { field ->
                            when {
                                field["notNull"]?.jsonPrimitive?.boolean != true -> "NULL"
                                field.getValue("affinity").jsonPrimitive.content == "TEXT" ->
                                    "'fixture'"
                                else -> "1"
                            }
                        }
                        c.execSQL("INSERT INTO `$table` VALUES ($values)")
                    }
                    c.execSQL(
                        "UPDATE queue_snapshot SET sourceJson='source-json-preserved', traversalJson='order-json-preserved', revision=42"
                    )
                    c.execSQL(
                        "UPDATE artists SET provider='youtube', kind='artist', sourceId='confirmed-artist'"
                    )
                    c.execSQL(
                        "UPDATE artist_merges SET preferredProvider='youtube', preferredKind='artist', preferredSourceId='confirmed-artist'"
                    )
                    c.execSQL("UPDATE offline_assets SET storageKey='offline-file-preserved'")
                    snapshot(c, tables)
                }
            val db = createMusicDatabase(path)
            try {
                assertEquals("CLASSIC", db.dao().setting("ui.theme")?.value)
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path).use { c ->
                assertEquals(before, snapshot(c, tables))
                c.prepare("PRAGMA user_version").use {
                    assertTrue(it.step())
                    assertEquals(8L, it.getLong(0))
                }
                c.prepare("SELECT COUNT(*) FROM artist_artwork").use {
                    assertTrue(it.step())
                    assertEquals(0L, it.getLong(0))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun olderMigrationChainsReachEight() = runBlocking {
        for (version in 1..6) {
            val directory = Files.createTempDirectory("molpy-chain-$version-").toFile()
            val path = directory.resolve("library.db").absolutePath
            try {
                createLegacy(path, version)
                val db = createMusicDatabase(path)
                try {
                    assertEquals("CLASSIC", db.dao().setting("ui.theme")?.value)
                } finally {
                    db.close()
                }
                BundledSQLiteDriver().open(path).use { c ->
                    c.prepare("PRAGMA user_version").use {
                        assertTrue(it.step())
                        assertEquals(8L, it.getLong(0))
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun existingThemeSettingIsNotReplaced() = runBlocking {
        for (theme in listOf("DARK", "LIGHT", "CLASSIC")) {
            val directory = Files.createTempDirectory("molpy-theme-migration-").toFile()
            val path = directory.resolve("library.db").absolutePath
            try {
                createLegacy(path, 7)
                BundledSQLiteDriver().open(path).use {
                    it.execSQL("INSERT INTO settings VALUES ('ui.theme','$theme')")
                }
                val db = createMusicDatabase(path)
                try {
                    assertEquals(theme, db.dao().setting("ui.theme")?.value)
                } finally {
                    db.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun freshEightHasNoLegacyClassicOverride() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            assertNull(store.db.dao().setting("ui.theme"))
            assertEquals(0, store.db.artistArtworkDao().count())
        } finally {
            store.close()
        }
    }

    private fun createLegacy(path: String, version: Int): JsonObject {
        val schema =
            Json.parseToJsonElement(
                    Files.readString(
                        Path.of("schemas/app.musicplayer.storage.MusicDatabase/$version.json")
                    )
                )
                .jsonObject
                .getValue("database")
                .jsonObject
        BundledSQLiteDriver().open(path).use { c ->
            schema.getValue("entities").jsonArray.forEach { raw ->
                val entity = raw.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                c.execSQL(
                    entity
                        .getValue("createSql")
                        .jsonPrimitive
                        .content
                        .replace("${'$'}{TABLE_NAME}", table)
                )
                entity["indices"]?.jsonArray.orEmpty().forEach {
                    c.execSQL(
                        it.jsonObject
                            .getValue("createSql")
                            .jsonPrimitive
                            .content
                            .replace("${'$'}{TABLE_NAME}", table)
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach {
                c.execSQL(it.jsonPrimitive.content)
            }
            c.execSQL("PRAGMA user_version=$version")
        }
        return schema
    }

    private fun snapshot(
        c: SQLiteConnection,
        tables: List<JsonObject>,
    ): Map<String, List<List<String>>> = tables.associate { entity ->
        val table = entity.getValue("tableName").jsonPrimitive.content
        val columns =
            entity.getValue("fields").jsonArray.map {
                it.jsonObject.getValue("columnName").jsonPrimitive.content
            }
        table to
            c.prepare(
                    "SELECT ${columns.joinToString { "quote(`$it`)" }} FROM `$table`" +
                        if (table == "settings") " WHERE `key` != 'ui.theme'" else ""
                )
                .use { statement ->
                    buildList {
                        while (statement.step()) add(columns.indices.map { statement.getText(it) })
                    }
                }
    }
}
