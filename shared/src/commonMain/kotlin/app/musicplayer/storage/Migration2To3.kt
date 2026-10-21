package app.musicplayer.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration2To3 : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `playlist_items` (
            `id` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `position` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"""
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_items_playlistId_position` ON `playlist_items` (`playlistId`, `position`)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_items_trackId` ON `playlist_items` (`trackId`)"
        )
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `listening_sessions` (
            `id` TEXT NOT NULL, `revision` INTEGER NOT NULL, `entryId` TEXT, `trackId` TEXT NOT NULL,
            `startedAtMs` INTEGER NOT NULL, `lastPlayedAtMs` INTEGER NOT NULL, `playedMs` INTEGER, `creditsJson` TEXT NOT NULL,
            PRIMARY KEY(`id`), FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"""
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_listening_sessions_trackId` ON `listening_sessions` (`trackId`)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_listening_sessions_startedAtMs_id` ON `listening_sessions` (`startedAtMs`, `id`)"
        )
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `track_listening` (
            `trackId` TEXT NOT NULL, `playedMs` INTEGER NOT NULL, `lastPlayedAtMs` INTEGER NOT NULL,
            PRIMARY KEY(`trackId`), FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"""
        )
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `artist_listening` (
            `key` TEXT NOT NULL, `name` TEXT NOT NULL, `provider` TEXT, `kind` TEXT, `sourceId` TEXT,
            `playedMs` INTEGER NOT NULL, `lastPlayedAtMs` INTEGER NOT NULL, PRIMARY KEY(`key`))"""
        )
        connection.execSQL(
            """INSERT INTO listening_sessions (id, revision, entryId, trackId, startedAtMs, lastPlayedAtMs, playedMs, creditsJson)
            SELECT 'legacy/' || trackId || '/' || lastPlayedAtMs, 0, NULL, trackId, lastPlayedAtMs, lastPlayedAtMs, NULL, '[]'
            FROM recent_history"""
        )
    }
}
