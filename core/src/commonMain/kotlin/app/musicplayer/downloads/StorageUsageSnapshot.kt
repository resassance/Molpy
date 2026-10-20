package app.musicplayer.downloads

import app.musicplayer.model.AssetId

/** Physical owned media/resource bytes. Shared resources count once in total and for each referencing asset. */
data class StorageUsageSnapshot(val totalBytes: Long, val perAssetBytes: Map<AssetId, Long>)
