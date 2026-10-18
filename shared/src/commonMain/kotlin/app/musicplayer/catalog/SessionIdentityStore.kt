package app.musicplayer.catalog

import app.musicplayer.model.SourceRef
import app.musicplayer.model.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Session-only identities until the durable repository replaces this implementation. */
class SessionIdentityStore : IdentityStore {
    private val mutex = Mutex()
    private val identities = mutableMapOf<SourceRef, TrackId>()

    override suspend fun getOrCreate(source: SourceRef): TrackId = mutex.withLock {
        identities.getOrPut(source) { TrackId(Uuid.random().toString()) }
    }
}
