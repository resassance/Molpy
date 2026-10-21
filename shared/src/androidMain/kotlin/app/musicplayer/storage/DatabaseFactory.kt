package app.musicplayer.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun createMusicDatabase(context: Context): MusicDatabase =
    Room.databaseBuilder<MusicDatabase>(
            context.applicationContext,
            context.applicationContext.getDatabasePath("music.db").absolutePath,
        )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(Migration1To2, Migration2To3)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
