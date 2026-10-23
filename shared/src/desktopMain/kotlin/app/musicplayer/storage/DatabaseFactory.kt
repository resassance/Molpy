package app.musicplayer.storage

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun createMusicDatabase(path: String): MusicDatabase =
    Room.databaseBuilder<MusicDatabase>(path)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
