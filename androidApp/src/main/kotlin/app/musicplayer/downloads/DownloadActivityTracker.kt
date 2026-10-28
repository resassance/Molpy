package app.musicplayer.downloads

import app.musicplayer.model.*
import java.util.UUID
import kotlinx.coroutines.flow.*

internal class DownloadActivityTracker(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Entry(
        val owner: String,
        val native: DownloadState,
        val session: String?,
        val lastReport: Long,
    )

    private val entries = mutableMapOf<AssetId, Entry>()
    private val mutable = MutableStateFlow<Map<AssetId, DownloadActivity>>(emptyMap())
    val states = mutable.asStateFlow()

    @Synchronized
    fun native(id: AssetId, owner: String, state: DownloadState, bytes: Long, total: Long?) {
        val old = entries[id]
        if (state in setOf(DownloadState.PAUSED, DownloadState.FAILED, DownloadState.AVAILABLE)) {
            if (old?.owner == owner) {
                entries[id] = old.copy(native = state, session = null)
                mutable.value = mutable.value - id
            }
            return
        }
        if (old?.owner == owner && old.native == state) return
        if (old?.owner == owner && state == DownloadState.DOWNLOADING && old.session != null) {
            entries[id] = old.copy(native = state)
            return
        }
        entries[id] = Entry(owner, state, null, clock())
        mutable.value =
            mutable.value +
                (id to
                    DownloadActivity(
                        if (state == DownloadState.QUEUED) DownloadPhase.QUEUED
                        else DownloadPhase.PREPARING,
                        bytes,
                        total,
                        UUID.randomUUID().toString(),
                    ))
    }

    @Synchronized
    fun begin(id: AssetId, owner: String, offset: Long, total: Long?): String? {
        val old =
            entries[id]?.takeIf {
                it.owner == owner &&
                    it.native in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)
            } ?: return null
        if (mutable.value[id]?.phase == DownloadPhase.VERIFYING) return null
        val session = UUID.randomUUID().toString()
        entries[id] = old.copy(session = session, lastReport = clock())
        mutable.value =
            mutable.value +
                (id to DownloadActivity(DownloadPhase.PREPARING, offset, total, session))
        return session
    }

    @Synchronized
    fun bytes(id: AssetId, session: String, bytes: Long) {
        val entry = entries[id]?.takeIf { it.session == session } ?: return
        val old = mutable.value[id]?.takeUnless { it.phase == DownloadPhase.VERIFYING } ?: return
        if (old.phase == DownloadPhase.DOWNLOADING && clock() - entry.lastReport < 1000) return
        entries[id] = entry.copy(lastReport = clock())
        mutable.value =
            mutable.value + (id to old.copy(phase = DownloadPhase.DOWNLOADING, bytes = bytes))
    }

    @Synchronized
    fun verifying(id: AssetId, owner: String, bytes: Long, total: Long?) {
        val entry = entries[id]
        if (entry != null && entry.owner != owner) return
        entries[id] = Entry(owner, DownloadState.DOWNLOADING, null, clock())
        mutable.value =
            mutable.value +
                (id to
                    DownloadActivity(
                        DownloadPhase.VERIFYING,
                        bytes,
                        total ?: mutable.value[id]?.totalBytes,
                        mutable.value[id]?.attempt ?: UUID.randomUUID().toString(),
                    ))
    }

    @Synchronized
    fun forget(id: AssetId) {
        entries.remove(id)
        mutable.value = mutable.value - id
    }
}
