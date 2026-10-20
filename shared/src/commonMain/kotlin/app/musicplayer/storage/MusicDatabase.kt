package app.musicplayer.storage

import androidx.room.*

@Database(entities = [TrackRecord::class, ExternalIdentityRecord::class, CandidateRecord::class,
    FavoriteRecord::class, RecentRecord::class, QueueEntryRecord::class, QueueSnapshotRecord::class,
    LyricsRecord::class, CatalogCacheRecord::class, SettingsRecord::class, OfflineRecord::class], version = 2, exportSchema = true)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao
    abstract fun offlineDao(): OfflineDao
}

@Suppress("KotlinNoActualForExpect")
expect object MusicDatabaseConstructor : RoomDatabaseConstructor<MusicDatabase> {
    override fun initialize(): MusicDatabase
}
