package app.musicplayer.ui.theme

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteModelTest {
    @Test
    fun closedCollectionDropsNoncancellableArtworkResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model =
            PaletteModel(
                {
                    withContext(NonCancellable) { gate.await() }
                    AppResult.Success(IntArray(32) { 0xffee4422.toInt() })
                },
                backgroundScope,
            )
        model.update(Artwork(ArtworkKind.RELEASE, "https://art/closed", null))
        runCurrent()
        model.close()
        gate.complete(Unit)
        runCurrent()
        assertEquals(ArtworkPalette.fromPixels(intArrayOf()), model.state.value)
    }

    @Test
    fun collectionArtworkDoesNotChangePlaybackPalette() = runTest {
        val playbackArtwork = Artwork(ArtworkKind.RELEASE, "https://art/playback", null)
        val collectionArtwork = Artwork(ArtworkKind.RELEASE, "https://art/collection", null)
        val sample: suspend (Artwork) -> AppResult<IntArray> = { artwork ->
            AppResult.Success(
                IntArray(32) {
                    if (artwork == playbackArtwork) 0xff2244ee.toInt() else 0xffee4422.toInt()
                }
            )
        }
        val playback = PaletteModel(sample, backgroundScope)
        val collection = PaletteModel(sample, backgroundScope)
        playback.update(TrackId("playing"), playbackArtwork)
        runCurrent()
        val playingColors = playback.state.value
        collection.update(collectionArtwork)
        runCurrent()
        assertNotEquals(playingColors, collection.state.value)
        assertEquals(playingColors, playback.state.value)
        collection.update(null)
        runCurrent()
        assertEquals(ArtworkPalette.fromPixels(intArrayOf()), collection.state.value)
        assertEquals(playingColors, playback.state.value)
        collection.close()
        playback.close()
    }

    @Test
    fun lateArtworkCannotRecolorNewCollection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = Artwork(ArtworkKind.RELEASE, "https://art/first", null)
        val second = Artwork(ArtworkKind.RELEASE, "https://art/second", null)
        val model =
            PaletteModel(
                { artwork ->
                    if (artwork == first) withContext(NonCancellable) { gate.await() }
                    AppResult.Success(
                        IntArray(32) {
                            if (artwork == first) 0xffee2222.toInt() else 0xff2244ee.toInt()
                        }
                    )
                },
                backgroundScope,
            )
        model.update(first)
        runCurrent()
        model.update(second)
        runCurrent()
        val current = model.state.value
        gate.complete(Unit)
        runCurrent()
        assertEquals(current, model.state.value)
        model.close()
    }

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
