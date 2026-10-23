package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration5To6 : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE queue_snapshot ADD COLUMN shuffleEnabled INTEGER NOT NULL DEFAULT 0"
        )
        connection.execSQL("ALTER TABLE queue_snapshot ADD COLUMN traversalJson TEXT")
    }
}
