package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtistMergeModelTest {
    @Test
    fun staleMergeRequiresConfirmationOfUpdatedScope() = runTest {
        val repo = ArtistModelRepository()
        val model = ArtistMergeModel(repo, backgroundScope)
        model.preview(repo.a, repo.b, null)
        runCurrent()
        assertEquals(1, model.state.value.merge?.trackCount)
        repo.mergeScope = "two"
        repo.mergeResult = ArtistWriteResult.STALE
        model.confirm()
        runCurrent()
        assertTrue(model.state.value.stale)
        assertEquals(2, model.state.value.merge?.trackCount)
        assertFalse(model.state.value.saving)
        repo.mergeResult = ArtistWriteResult.APPLIED
        model.confirm()
        runCurrent()
        assertNull(model.state.value.merge)
        model.close()
    }

    @Test
    fun splitPreviewCanBeDismissedAndStaleScopeIsRefreshed() = runTest {
        val repo = ArtistModelRepository()
        val model = ArtistMergeModel(repo, backgroundScope)
        model.previewSplit(repo.a)
        runCurrent()
        assertEquals(repo.a, model.state.value.split?.origin)
        model.closeDraft()
        assertNull(model.state.value.split)
        model.previewSplit(repo.a)
        runCurrent()
        repo.mergeScope = "two"
        repo.mergeResult = ArtistWriteResult.STALE
        model.confirmSplit()
        runCurrent()
        assertTrue(model.state.value.stale)
        assertEquals("two", model.state.value.split?.scopeKey)
        repo.mergeResult = ArtistWriteResult.APPLIED
        model.confirmSplit()
        runCurrent()
        assertNull(model.state.value.split)
        model.close()
    }
}
