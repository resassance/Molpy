package app.musicplayer.storage

import app.musicplayer.collections.*
import app.musicplayer.listening.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class ArtworkRetentionTest {
    @Test
    fun playlistAndListeningOwnersKeepLocalArtworkAfterLegacyOwnersDisappear() = runBlocking {
        val store = TestLibrary()
        try {
            store.open()
            val playlistTrack =
                fixture("playlist").let {
                    it.copy(artwork = it.artwork.copy(localKey = "/private/playlist.artwork"))
                }
            val historyTrack =
                fixture("history").let {
                    it.copy(artwork = it.artwork.copy(localKey = "/private/history.artwork"))
                }
            val cachedTrack =
                fixture("unowned").let {
                    it.copy(artwork = it.artwork.copy(localKey = "/private/cache.artwork"))
                }
            val collections = RoomCollectionsRepository(store.db) { 0L }
            val id = assertIs<AppResult.Success<PlaylistId>>(collections.create("Mix")).value
            assertIs<AppResult.Success<PlaylistItemId>>(collections.append(id, playlistTrack))
            val listening = RoomListeningRepository(store.db) { 0L }
            assertIs<AppResult.Success<Unit>>(
                listening.save(
                    ListeningCheckpoint(
                        "session",
                        1,
                        QueueEntryId("entry"),
                        historyTrack,
                        1,
                        1001,
                        1000,
                    )
                )
            )
            store.repository.upsert(cachedTrack)
            store.now = Long.MAX_VALUE
            store.repository.prune(store.now)
            assertEquals(
                setOf("/private/playlist.artwork", "/private/history.artwork"),
                store.repository.retainedArtworkKeys(),
            )
            collections.delete(id)
            store.repository.prune(store.now)
            assertEquals(setOf("/private/history.artwork"), store.repository.retainedArtworkKeys())
            assertEquals(historyTrack, store.repository.getTrack(historyTrack.id))
        } finally {
            store.close()
        }
    }
}
