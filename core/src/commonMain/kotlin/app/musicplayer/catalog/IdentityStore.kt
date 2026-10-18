package app.musicplayer.catalog

import app.musicplayer.model.SourceRef
import app.musicplayer.model.TrackId

/** Atomically reuses an app ID for an exact durable provider identity. */
interface IdentityStore {
    suspend fun getOrCreate(source: SourceRef): TrackId
}
