package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration7To8 : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `artist_artwork` (`sourceKey` TEXT NOT NULL, `provider` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `artworkJson` TEXT, `checkedAtMs` INTEGER NOT NULL, `refreshAfterMs` INTEGER NOT NULL, `outcome` TEXT NOT NULL, PRIMARY KEY(`sourceKey`))"
        )
        connection.execSQL(
            "INSERT OR IGNORE INTO settings(key,value) VALUES ('ui.theme','CLASSIC')"
        )
    }
}
