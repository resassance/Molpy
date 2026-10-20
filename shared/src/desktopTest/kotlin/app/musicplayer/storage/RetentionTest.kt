package app.musicplayer.storage

import app.musicplayer.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RetentionTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun recentHistoryKeepsOneHundredDistinctTracksInLastStartedOrder() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            repeat(105) { index ->
                val track = fixture("history-$index")
                library.repository.upsert(track)
                library.repository.recordStarted(track.id, index.toLong())
            }
            library.repository.recordStarted(TrackId("history-5"), 200)
            library.reopen()
            val recent = library.repository.recent.first()
            assertEquals(100, recent.size)
            assertEquals(TrackId("history-5"), recent.first().id)
            assertEquals(100, recent.map { it.id }.toSet().size)
            assertFalse(recent.any { it.id == TrackId("history-4") })
        } finally { library.close() }
    }

    @Test fun expiredMetadataIsPrunedButFavoriteQueueRecentLyricsAndIdentitySurvive() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            val old = fixture("old"); val favorite = fixture("favorite"); val queued = fixture("queued"); val recent = fixture("recent"); val lyrics = fixture("lyrics")
            listOf(old, favorite, queued, recent, lyrics).forEach { library.repository.upsert(it) }
            library.repository.setFavorite(favorite.id, true)
            library.repository.recordStarted(recent.id, 0)
            val entry = QueueEntry(QueueEntryId("entry"), queued.id)
            library.repository.saveQueue(QueueSnapshot(listOf(entry), entry.id, 0, false))
            val positive = LyricsLookup.Available(NormalizedLyrics("Saved words", emptyList(), LyricsKind.PLAIN, SourceRef("lyrics", "entry", "saved")))
            library.repository.saveLyrics(lyrics.id, lyrics.candidates.single().id, positive, 0)
            library.now = 7 * day
            library.repository.prune(library.now)
            assertNull(library.repository.getTrack(old.id))
            listOf(favorite, queued, recent, lyrics).forEach { assertNotNull(library.repository.getTrack(it.id)) }
            assertEquals(old.id, library.repository.getOrCreate(old.externalIds.single()))
            library.reopen()
            assertEquals(positive, library.repository.getLyrics(lyrics.id, lyrics.candidates.single().id, library.now))
        } finally { library.close() }
    }

    @Test fun metadataLimitEvictsOldestUnprotectedRecordsOnly() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            val favorite = fixture("protected"); library.repository.upsert(favorite); library.repository.setFavorite(favorite.id, true)
            repeat(1002) { index -> library.now = index.toLong(); library.repository.upsert(fixture("cache-$index")) }
            library.repository.prune(library.now)
            assertNotNull(library.repository.getTrack(favorite.id))
            assertNull(library.repository.getTrack(TrackId("cache-0")))
            assertNull(library.repository.getTrack(TrackId("cache-1")))
            assertNotNull(library.repository.getTrack(TrackId("cache-2")))
            assertNotNull(library.repository.getTrack(TrackId("cache-1001")))
        } finally { library.close() }
    }

    @Test fun searchExpiresAfterFifteenMinutesAndMissingMetadataInvalidatesWholeResult() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            val track = fixture("search")
            library.repository.cacheSearch("query", true, listOf(track, track), 100)
            assertEquals(listOf(track, track), library.repository.getCachedSearch("query", true, 900_099))
            assertNull(library.repository.getCachedSearch("query", true, 900_100))
            assertNull(library.repository.getCachedSearch("query", true, 99))
            library.repository.cacheSearch("empty", true, emptyList(), 0)
            assertEquals(emptyList(), library.repository.getCachedSearch("empty", true, 0))
            library.now = 7 * day
            library.repository.prune(library.now)
            library.repository.cacheSearch("partial", true, listOf(track), library.now)
            library.now += 7 * day
            library.repository.prune(library.now)
            assertNull(library.repository.getCachedSearch("partial", true, library.now))
        } finally { library.close() }
    }

    @Test fun unavailableLyricsExpireAtTwentyFourHoursAndFailuresAreNeverNegativeCached() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            val track = fixture("lyrics"); library.repository.upsert(track)
            val candidate = track.candidates.single().id
            library.repository.saveLyrics(track.id, candidate, LyricsLookup.Unavailable, 10)
            assertEquals(LyricsLookup.Unavailable, library.repository.getLyrics(track.id, candidate, day + 9))
            assertNull(library.repository.getLyrics(track.id, candidate, day + 10))
            val failed = CandidateId("failed")
            library.repository.saveLyrics(track.id, failed, LyricsLookup.Failed(AppError(ErrorCode.NETWORK, "failed", true)), 0)
            assertNull(library.repository.getLyrics(track.id, failed, 0))
            library.repository.saveLyrics(track.id, candidate, LyricsLookup.Unavailable, 10)
            assertNull(library.repository.getLyrics(track.id, candidate, 9))
            library.repository.prune(day + 10)
            library.reopen()
            assertNull(library.repository.getLyrics(track.id, candidate, day + 10))
        } finally { library.close() }
    }
}
