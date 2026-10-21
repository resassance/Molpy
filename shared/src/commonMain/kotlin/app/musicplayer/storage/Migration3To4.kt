package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration3To4 : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `audio_metadata` (`candidateId` TEXT NOT NULL, `provider` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `representationId` TEXT NOT NULL, `relativeLoudnessDb` REAL, PRIMARY KEY(`candidateId`, `provider`, `kind`, `sourceId`, `representationId`))"
        )
    }
}
