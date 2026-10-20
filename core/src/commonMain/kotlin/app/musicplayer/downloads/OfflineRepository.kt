package app.musicplayer.downloads

import app.musicplayer.model.AssetId
import app.musicplayer.model.TrackId

interface OfflineRepository {
    suspend fun findAvailable(trackId: TrackId): OfflineAsset?
    suspend fun findAsset(id: AssetId): OfflineAsset?
    /** Minimal enumeration for startup DownloadIndex projection and explicit remove-all. */
    suspend fun listAssets(): List<OfflineAsset>
    /** The platform owner publishes AVAILABLE only after complete transport and verification. */
    suspend fun saveMapping(asset: OfflineAsset)
    suspend fun removeMapping(id: AssetId)
    /** Incomplete transfers retain identity even when they have not written a storage span. */
    suspend fun reconcile(existingStorageKeys: Set<String>)
}
