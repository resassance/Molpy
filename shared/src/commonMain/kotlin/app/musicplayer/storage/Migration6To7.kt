package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration6To7 : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE queue_snapshot ADD COLUMN sourceJson TEXT")
        connection.execSQL(
            "ALTER TABLE queue_snapshot ADD COLUMN revision INTEGER NOT NULL DEFAULT 0"
        )
    }
}
