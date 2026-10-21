package app.musicplayer.ui.theme

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteModelTest {
    @Test
    fun lateCoverCannotRecolorNewTrackAndFailuresStayNeutral() = runTest {
        val gate = CompletableDeferred<Unit>()
        val red = Artwork(ArtworkKind.RELEASE, "https://art/a", null)
        val blue = Artwork(ArtworkKind.RELEASE, "https://art/b", null)
        val model =
            PaletteModel(
                { art ->
                    if (art == red) withContext(NonCancellable) { gate.await() }
                    AppResult.Success(
                        IntArray(32) { if (art == red) 0xffee2222.toInt() else 0xff2244ee.toInt() }
                    )
                },
                backgroundScope,
            )
        model.update(TrackId("a"), red)
        runCurrent()
        model.update(TrackId("b"), blue)
        runCurrent()
        val selected = model.state.value
        gate.complete(Unit)
        runCurrent()
        assertEquals(selected, model.state.value)
        model.close()
    }
}
