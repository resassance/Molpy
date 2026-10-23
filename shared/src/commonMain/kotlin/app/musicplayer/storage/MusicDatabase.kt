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
            ArtistProfileRecord::class,
            ArtistAliasRecord::class,
            TrackArtistOverrideRecord::class,
            ArtistMergeRecord::class,
            SessionArtistRecord::class,
        ],
    version = 6,
    exportSchema = true,
)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao

    abstract fun audioMetadataDao(): AudioMetadataDao

    abstract fun offlineDao(): OfflineDao

    abstract fun collectionDao(): CollectionDao

    abstract fun artistDao(): ArtistDao

    abstract fun listeningDao(): ListeningDao
}

@Suppress("KotlinNoActualForExpect")
expect object MusicDatabaseConstructor : RoomDatabaseConstructor<MusicDatabase> {
    override fun initialize(): MusicDatabase
}
