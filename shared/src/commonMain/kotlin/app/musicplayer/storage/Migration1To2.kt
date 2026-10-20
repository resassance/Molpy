package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Room 2.8 SQLiteDriver migration. Schema 1 tables and their data remain untouched. */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS `offline_assets` (
            `id` TEXT NOT NULL, `trackId` TEXT NOT NULL, `snapshotJson` TEXT NOT NULL,
            `candidateId` TEXT NOT NULL, `provider` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
            `representationId` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `codec` TEXT NOT NULL,
            `expectedBytes` INTEGER, `expectedDurationMs` INTEGER, `validator` TEXT,
            `storageKey` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `state` TEXT NOT NULL,
            PRIMARY KEY(`id`), FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_assets_trackId_candidateId_representationId` ON `offline_assets` (`trackId`, `candidateId`, `representationId`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_assets_storageKey` ON `offline_assets` (`storageKey`)")
    }
}
