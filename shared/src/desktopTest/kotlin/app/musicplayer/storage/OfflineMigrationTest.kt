package app.musicplayer.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.musicplayer.downloads.*
import app.musicplayer.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class OfflineMigrationTest {
    private fun asset(track: Track, id: String = "asset") = OfflineAsset(AssetId(id), track.id, track,
        track.candidates.single().id, track.candidates.single().source,
        Representation("format", "audio/test", "test", 10, 120000, "etag-v1"), "storage-$id", 10, DownloadState.AVAILABLE)

    @Test fun realExportedSchemaOneMigratesWithFavoritesDuplicateQueueLyricsAndSourceIdentity() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("molpy-migrate-").toFile()
        val path = directory.resolve("library.db").absolutePath
        val schema = Json.parseToJsonElement(Files.readString(Path.of("schemas/app.musicplayer.storage.MusicDatabase/1.json"))).jsonObject["database"]!!.jsonObject
        val before = fixture("legacy")
        val lyrics = LyricsLookup.Instrumental(SourceRef("lyrics", "entry", "synthetic"))
        BundledSQLiteDriver().open(path).use { connection ->
            schema["entities"]!!.jsonArray.forEach { entity ->
                val fields = entity.jsonObject
                val table = fields["tableName"]!!.jsonPrimitive.content
                connection.execSQL(fields["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                fields["indices"]?.jsonArray.orEmpty().forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 1")
            fun insert(table: String, values: List<Any?>) {
                connection.prepare("INSERT INTO $table VALUES (${values.joinToString { "?" }})").use { stmt ->
                    values.forEachIndexed { i, v -> when(v) {
                        null -> stmt.bindNull(i+1)
                        is String -> stmt.bindText(i+1, v)
                        is Boolean -> stmt.bindLong(i+1, if(v) 1 else 0)
                        is Number -> stmt.bindLong(i+1, v.toLong())
                    } }; stmt.step()
                }
            }
            val t = RecordMapper.track(before, before.id, 0)
            insert("tracks", listOf(t.id,t.recordVersion,t.title,t.artistsJson,t.albumJson,t.artworkKind,t.artworkUrl,t.artworkLocalKey,t.durationMs,t.provenanceProvider,t.provenanceKind,t.provenanceId,t.fetchedAtMs,t.tentative,t.cachedAtMs))
            before.externalIds.forEach { insert("external_identity", listOf(it.provider,it.kind,it.id,before.id.value)) }
            RecordMapper.candidates(before,before.id).forEach { insert("candidates",listOf(it.trackId,it.candidateId,it.position,it.provider,it.kind,it.sourceId,it.version,it.durationMs,it.confidence)) }
            insert("favorites", listOf(before.id.value,5))
            insert("recent_history", listOf(before.id.value,6))
            insert("queue_entries", listOf("first",before.id.value,0)); insert("queue_entries",listOf("second",before.id.value,1))
            insert("queue_snapshot",listOf(1,"second",4321,true))
            val l = RecordMapper.lyrics(before.id,before.candidates.single().id,lyrics,0)
            insert("lyrics_cache",listOf(l.trackId,l.candidateId,l.recordVersion,l.kind,l.payloadJson,l.quality,l.cachedAtMs))
        }
        val db = createMusicDatabase(path)
        try {
            val repository = RoomLibraryRepository(db) { 0 }
            assertEquals(listOf(before), repository.favorites.first()) // Opens Room and validates migrated schema.
            assertEquals(listOf(before), repository.recent.first())
            assertEquals(before,repository.getTrack(before.id))
            assertEquals(before.id,repository.getOrCreate(before.externalIds.single()))
            assertEquals(listOf("first","second"),repository.restoreQueue().entries.map { it.id.value })
            assertEquals(4321,repository.restoreQueue().positionMs)
            assertFalse(repository.restoreQueue().playWhenReady)
            assertEquals(lyrics,repository.getLyrics(before.id,before.candidates.single().id,Long.MAX_VALUE))
            assertTrue(repository.listAssets().isEmpty())
            repository.saveMapping(asset(before))
            assertEquals(asset(before),repository.findAvailable(before.id))
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun freshSchemaPersistsIndependentSnapshotAndRemovesOnlyOfflineAvailability() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            val original = fixture("downloaded")
            library.repository.upsert(original)
            library.repository.setFavorite(original.id,true)
            library.repository.recordStarted(original.id,1)
            val saved = asset(original)
            library.repository.saveMapping(saved)
            library.repository.upsert(original.copy(title="Refreshed", candidates=emptyList()))
            library.reopen()
            assertEquals(saved,library.repository.findAsset(saved.id))
            assertEquals(listOf(saved),library.repository.listAssets())
            library.repository.reconcile(setOf(saved.storageKey))
            assertEquals(saved,library.repository.findAvailable(original.id))
            library.repository.reconcile(emptySet())
            assertNull(library.repository.findAvailable(original.id))
            assertNull(library.repository.findAsset(saved.id))
            assertEquals("Refreshed",library.repository.getTrack(original.id)?.title)
            assertEquals(1,library.repository.favorites.first().size)
            assertEquals(1,library.repository.recent.first().size)
            library.repository.saveMapping(saved)
            library.repository.removeMapping(saved.id)
            assertNotNull(library.repository.getTrack(original.id))
            library.repository.removeMapping(saved.id)
        } finally { library.close() }
    }

    @Test fun transferProjectionsKeepIdentityWithoutPublishingAvailabilityAndDuplicatesDoNotMultiply() = runBlocking<Unit> {
        val library = TestLibrary()
        try {
            library.open()
            val track=fixture("dedup"); val saved=asset(track)
            for (state in DownloadState.entries.filter { it != DownloadState.AVAILABLE }) {
                library.repository.saveMapping(saved.copy(state=state))
                assertEquals(state,library.repository.findAsset(saved.id)?.state)
                assertNull(library.repository.findAvailable(track.id))
                library.repository.reconcile(emptySet())
                assertNotNull(library.repository.findAsset(saved.id))
            }
            assertEquals(1,library.repository.listAssets().size)
            library.repository.saveMapping(saved)
            library.repository.saveMapping(saved)
            assertEquals(1,library.repository.listAssets().size)
            assertFailsWith<IllegalArgumentException> { library.repository.saveMapping(saved.copy(id=AssetId("duplicate"),storageKey="other")) }
            val other=saved.copy(id=AssetId("format2"),storageKey="format2",representation=saved.representation.copy(id="format2"))
            library.repository.saveMapping(other)
            assertEquals(2,library.repository.listAssets().size)
        } finally { library.close() }
    }

    @Test fun explicitLocalArtworkUpdatePreservesRecordingIdentityAndRejectsMetadataReassignment() = runBlocking<Unit> {
        val library=TestLibrary()
        try {
            library.open()
            val track=fixture("artwork"); val saved=asset(track)
            library.repository.saveMapping(saved)
            val enriched=saved.copy(trackSnapshot=track.copy(artwork=track.artwork.copy(localKey="saved-cover")))
            library.repository.saveMapping(enriched)
            library.reopen()
            assertEquals(enriched,library.repository.findAsset(saved.id))
            assertEquals(track,library.repository.getTrack(track.id))
            assertFailsWith<IllegalArgumentException> {
                library.repository.saveMapping(enriched.copy(trackSnapshot=enriched.trackSnapshot.copy(title="Different recording")))
            }
            assertFailsWith<IllegalArgumentException> {
                library.repository.saveMapping(enriched.copy(representation=enriched.representation.copy(id="other")))
            }
            assertEquals(enriched,library.repository.findAsset(saved.id))
        } finally { library.close() }
    }

    @Test fun offlineReferencesProtectMetadataAndSavedLyricsUntilExplicitMappingRemoval() = runBlocking<Unit> {
        val library=TestLibrary()
        try {
            library.open()
            val track=fixture("protected"); val saved=asset(track)
            library.repository.saveMapping(saved)
            library.repository.saveLyrics(track.id,saved.candidateId,LyricsLookup.Unavailable,0)
            library.repository.prune(Long.MAX_VALUE)
            assertNotNull(library.repository.getTrack(track.id))
            assertEquals(LyricsLookup.Unavailable,library.repository.getLyrics(track.id,saved.candidateId,Long.MAX_VALUE))
            library.reopen()
            assertEquals(saved,library.repository.findAvailable(track.id))
            library.repository.removeMapping(saved.id)
            library.repository.prune(Long.MAX_VALUE)
            assertNull(library.repository.getLyrics(track.id,saved.candidateId,Long.MAX_VALUE))
            assertNull(library.repository.getTrack(track.id))
        } finally { library.close() }
    }
}
