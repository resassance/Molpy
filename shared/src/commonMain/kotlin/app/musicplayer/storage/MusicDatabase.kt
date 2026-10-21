package app.musicplayer.storage

import androidx.room.*

@Database(
    entities =
        [
            TrackRecord::class,
            AudioMetadataRecord::class,
            ExternalIdentityRecord::class,
            CandidateRecord::class,
            FavoriteRecord::class,
            RecentRecord::class,
            QueueEntryRecord::class,
            QueueSnapshotRecord::class,
            LyricsRecord::class,
            CatalogCacheRecord::class,
            SettingsRecord::class,
            OfflineRecord::class,
            PlaylistRecord::class,
            PlaylistItemRecord::class,
            ListeningSessionRecord::class,
            TrackListeningRecord::class,
            ArtistListeningRecord::class,
        ],
    version = 4,
    exportSchema = true,
)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao

    abstract fun audioMetadataDao(): AudioMetadataDao

    abstract fun offlineDao(): OfflineDao

    abstract fun collectionDao(): CollectionDao

    abstract fun listeningDao(): ListeningDao
}

@Suppress("KotlinNoActualForExpect")
expect object MusicDatabaseConstructor : RoomDatabaseConstructor<MusicDatabase> {
    override fun initialize(): MusicDatabase
}
