package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.model.*
import app.musicplayer.ui.artists.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ArtistEditorPersistenceTest {
    @Test
    fun selectingValidatedProfileChangesOnlyCurrentTrackAfterReopen() =
        runBlocking<Unit> {
            val library = TestLibrary()
            try {
                library.open()
                val repo = RoomArtistRepository(library.db) { 0 }
                val x = artistTrack("x", "Original")
                val y = artistTrack("y", "Original")
                val original = repo.ensure(y).artistOk().artists
                val search =
                    object : ArtistSearchPort {
                        override suspend fun search(
                            query: String
                        ): AppResult<List<ArtistCatalogHit>> = error("No catalog query needed")

                        override suspend fun validate(hit: ArtistCatalogHit) =
                            AppResult.Success(hit.copy(name = "Validated"))
                    }
                val model = ArtistEditorModel(repo, search, this)
                model.open(x)
                withTimeout(5000) { model.state.first { it.track?.id == x.id && !it.saving } }
                val selected =
                    ArtistCatalogHit("Candidate", SourceRef("youtube", "artist", "chosen"), null)
                model.select(0, ArtistChoice.Catalog(selected))
                model.save()
                withTimeout(5000) {
                    model.state.first { it.saved || !it.saving && it.error != null }
                }
                assertTrue(model.state.value.saved)
                model.close()
                assertEquals(original, repo.ensure(y).artistOk().artists)
                assertEquals(x, library.repository.getTrack(x.id))
                library.reopen()
                val current = RoomArtistRepository(library.db) { 0 }
                val d = current.directory.first().artistOk()
                val presented = ArtistResolver.present(x, d)
                assertEquals("Validated", presented.artists.single().name)
                assertEquals(selected.source, presented.artists.single().source)
                assertEquals(original, ArtistResolver.present(y, d).artists)
                current.resetOverride(x.id, d.overrides[x.id]).artistOk()
                assertEquals(
                    "Original",
                    ArtistResolver.present(x, current.directory.first().artistOk())
                        .artists
                        .single()
                        .name,
                )
            } finally {
                library.close()
            }
        }
}
