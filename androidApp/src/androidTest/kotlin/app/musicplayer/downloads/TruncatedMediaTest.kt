package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original PCM16 generated in memory. These tests require a real Android extractor/codec runtime. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TruncatedMediaTest {
    @Test fun completeGeneratedPcm16WavVerifies() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            populate(cache, asset, audio)
            assertTrue(DownloadVerifier(cache) { true }.verify(asset) is AppResult.Success)
            assertEquals(DownloadState.DOWNLOADING, asset.state)
        }
    }

    @Test fun headerAdvertising120SecondsWithOnlyTenSecondsOfPcmNeverVerifies() = runBlocking {
        withCache { cache ->
            val audio = wav(120, 10)
            // Exact HTTP-200 body length still cannot erase a measured duration mismatch.
            val asset = asset(audio.size.toLong(), 120_000)
            populate(cache, asset, audio)
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
    }

    @Test fun unknownRemoteLengthRequiresIndependentDurationAndCountsActualPcm() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(null, 10_000)
            populate(cache, asset, audio)
            assertTrue(DownloadVerifier(cache) { true }.verify(asset) is AppResult.Success)
            val absentDuration = asset.copy(representation = asset.representation.copy(expectedDurationMs = null),
                trackSnapshot = asset.trackSnapshot.copy(durationMs = null,
                    candidates = asset.trackSnapshot.candidates.map { it.copy(durationMs = null) }))
            incomplete(DownloadVerifier(cache) { true }.verify(absentDuration))
        }
    }

    @Test fun admittedStampLengthRejectsShortOrExtraCacheBytesWhenAssetLengthIsUnknown() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(null, 10_000)
            populate(cache, asset, audio)
            for (expected in listOf(audio.size.toLong() - 1, audio.size.toLong() + 1)) {
                ResourceStampMetadata.write(cache, asset.storageKey,
                    ResourceStamp(asset.source, asset.representation.id, null, expected))
                incomplete(DownloadVerifier(cache) { true }.verify(asset))
            }
        }
    }

    @Test fun matchingAdmittedStampLengthProvidesExactByteEvidenceWithoutSourceDuration() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(null, null)
            populate(cache, asset, audio)
            ResourceStampMetadata.write(cache, asset.storageKey,
                ResourceStamp(asset.source, asset.representation.id, null, audio.size.toLong()))
            assertTrue(DownloadVerifier(cache) { true }.verify(asset) is AppResult.Success)
        }
    }

    @Test fun generatedWebmOpusVerifiesThroughFullDecoderOutputEos() = runBlocking {
        withCache { cache ->
            val audio = generatedWebmOpus()
            val initial = asset(audio.size.toLong(), 10_000)
            val asset = initial.copy(representation = initial.representation.copy(
                mimeType = "audio/webm; codecs=\"opus\"", codec = "opus"))
            populate(cache, asset, audio)
            assertTrue("Requires platform WebM extraction and Opus decoding to actual PCM/output EOS",
                DownloadVerifier(cache) { true }.verify(asset) is AppResult.Success)
        }
    }

    @Test fun webmMimeCannotRelabelWavBytesAsWebm() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val initial = asset(audio.size.toLong(), 10_000)
            val asset = initial.copy(representation = initial.representation.copy(mimeType = "audio/webm", codec = "opus"))
            populate(cache, asset, audio)
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
    }

    @Test fun selectedCandidateDurationIsUsedWhenRepresentationDurationIsAbsent() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val initial = asset(audio.size.toLong(), 120_000)
            val asset = initial.copy(representation = initial.representation.copy(expectedDurationMs = null))
            populate(cache, asset, audio)
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
    }

    @Test fun interiorCacheHoleCannotFallBackToHttp() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            stamp(cache, asset)
            write(cache, asset.storageKey, 0, audio.copyOfRange(0, 100))
            write(cache, asset.storageKey, 200, audio.copyOfRange(200, audio.size))
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
            // The bridge itself also fails on a hole: its only upstream is Media3's placeholder.
            CacheMediaDataSource(cache, asset.storageKey, "musicplayer-download://asset/generated", audio.size.toLong()) {}.use { bridge ->
                try { bridge.readAt(100, ByteArray(10), 0, 10); fail("A missing span must fail closed") }
                catch (_: java.io.IOException) { }
                assertTrue(bridge.hasFailure)
            }
        }
    }

    @Test fun staleDeletedSpanAndPhysicallyTruncatedSpanBothFail() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            populate(cache, asset, audio)
            val span = cache.getCachedSpans(asset.storageKey).single()
            assertTrue(span.file!!.delete())
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            populate(cache, asset, audio)
            // Fault injection only: production verifier never opens a raw cache span file.
            RandomAccessFile(cache.getCachedSpans(asset.storageKey).single().file!!, "rw").use { it.setLength(70) }
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
    }

    @Test fun extraBytesAndConflictingEarlyEofMetadataFail() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            populate(cache, asset, audio)
            write(cache, asset.storageKey, audio.size.toLong(), byteArrayOf(1))
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(null, 10_000)
            populate(cache, asset, audio)
            cache.applyContentMetadataMutations(asset.storageKey, ContentMetadataMutations()
                .set(ContentMetadata.KEY_CONTENT_LENGTH, 70L))
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
        }
    }

    @Test fun transportCompletionDoesNotProveIdentity() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            write(cache, asset.storageKey, 0, audio)
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
            stamp(cache, asset)
            ResourceStampMetadata.write(cache, asset.storageKey, ResourceStamp(asset.source, "different", null, asset.representation.expectedBytes))
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
            stamp(cache, asset)
            ResourceStampMetadata.write(cache, asset.storageKey, ResourceStamp(asset.source.copy(id = "different"), asset.representation.id, null, asset.representation.expectedBytes))
            incomplete(DownloadVerifier(cache) { true }.verify(asset))
            stamp(cache, asset)
            incomplete(DownloadVerifier(cache) { false }.verify(asset))
            incomplete(DownloadVerifier(cache) { true }.verify(asset.copy(storageKey = "unbound-key")))
        }
    }

    @Test fun cancellationPropagatesInsteadOfBecomingSuccessOrAnAppError() = runBlocking {
        withCache { cache ->
            val audio = wav(10, 10)
            val asset = asset(audio.size.toLong(), 10_000)
            populate(cache, asset, audio)
            try { DownloadVerifier(cache) { throw CancellationException("fixture") }.verify(asset); fail("Expected cancellation") }
            catch (_: CancellationException) { }
        }
    }

    @Test fun cacheBridgeBoundariesAndStickyReadFailure() {
        runBlocking {
            withCache { cache ->
                val audio = wav(10, 10)
                val asset = asset(audio.size.toLong(), 10_000)
                populate(cache, asset, audio)
                CacheMediaDataSource(cache, asset.storageKey, "musicplayer-download://asset/generated", audio.size.toLong()) {}.use { bridge ->
                    assertEquals(0, bridge.readAt(0, ByteArray(0), 0, 0))
                    assertEquals(-1, bridge.readAt(audio.size.toLong(), ByteArray(1), 0, 1))
                    assertEquals(1, bridge.readAt(audio.lastIndex.toLong(), ByteArray(8), 0, 8))
                    assertEquals(4, bridge.readAt(0, ByteArray(4), 0, 4))
                    assertFalse(bridge.hasFailure)
                    cache.removeResource(asset.storageKey)
                    try { bridge.readAt(100, ByteArray(4), 0, 4); fail("Removed bytes must fail") }
                    catch (_: java.io.IOException) { }
                    assertTrue(bridge.hasFailure)
                    // Even a later legal EOF cannot erase the native callback's I/O failure.
                    assertEquals(-1, bridge.readAt(audio.size.toLong(), ByteArray(1), 0, 1))
                    assertTrue(bridge.hasFailure)
                }
            }
        }
    }

    private fun incomplete(result: AppResult<Unit>) = assertEquals(ErrorCode.INCOMPLETE_MEDIA, result.errorOrNull()?.code)

    @Suppress("DEPRECATION")
    private suspend fun withCache(block: suspend (SimpleCache) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "verifier-${java.util.UUID.randomUUID()}")
        val cache = SimpleCache(dir, NoOpCacheEvictor())
        try { cache.checkInitialization(); block(cache) }
        finally { cache.release(); dir.deleteRecursively() }
    }

    private fun asset(expectedBytes: Long?, duration: Long?): OfflineAsset {
        val source = SourceRef("fixture", "generated", "original-pcm16")
        val candidate = PlaybackCandidate(CandidateId("generated"), source, "original", duration, MatchConfidence.HIGH)
        val track = Track(TrackId("generated"), "Generated PCM16", emptyList(), null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null), duration, setOf(source), MetadataProvenance(source, 0, false), listOf(candidate))
        val representation = Representation("pcm16-wav", "audio/wav", "pcm_s16le", expectedBytes, duration, null)
        return OfflineAsset(AssetId("generated"), track.id, track, candidate.id, source, representation,
            DownloadPolicy().keyFor(track.id, candidate.id, representation.id), 0, DownloadState.DOWNLOADING)
    }

    private fun populate(cache: SimpleCache, asset: OfflineAsset, bytes: ByteArray) {
        stamp(cache, asset); write(cache, asset.storageKey, 0, bytes)
    }

    private fun stamp(cache: SimpleCache, asset: OfflineAsset) {
        ResourceStampMetadata.write(cache, asset.storageKey, ResourceStamp(asset.source, asset.representation.id,
            asset.representation.validator, asset.representation.expectedBytes))
    }

    private fun write(cache: SimpleCache, key: String, offset: Long, bytes: ByteArray) {
        val hole = cache.startReadWrite(key, offset, bytes.size.toLong())
        try {
            val file = cache.startFile(key, offset, bytes.size.toLong())
            file.outputStream().use { it.write(bytes) }
            cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
    }

    // Original ten-second mono silence, generated locally by FFmpeg/libopus, not provider media.
    // Gzip only keeps the source fixture small; the verifier receives the complete real WebM bytes.
    private fun generatedWebmOpus(): ByteArray = java.util.zip.GZIPInputStream(
        java.io.ByteArrayInputStream(android.util.Base64.decode(WEBM_OPUS_GZIP, android.util.Base64.DEFAULT))
    ).use { it.readBytes() }

    private companion object {
        const val WEBM_OPUS_GZIP = """
H4sIAAAAAAACA7XbfWhVZRzA8XN155znvD/PczMsjOzNXii5QoVFwTbvwoFz5jaXoKRuuiQta7ulULBzN7eZkxQzlIxtziDL
RNH1dhMmakzISEhMSHKkLGPRzMKKjO6dg/5o3z873Mvl+Z6X+3D/+Pzxg3tz2Xe9XaVtcaL0av79S1xUeiUWpdnWl5cvW13a
nl+ujydMrmpuSBiFI3VWVbzV9GlFbmPV+62TyvesqNob91xf3Vi979n86vOKXGd+pauPNlTtzSZS15dTqnL5k9lU1/D15xgL
jf/rKOyq+74zB1qi0uKK5tY5S19aUVsy+pHe0FF8tHP0osJmL+4b28uiM3Gi8XjH2P2JnbFxe9/Olsxz9R2x0dZW8nTlvJqq
Be+11E3vXpBrLTp2yGiJJwxu6YoTfR3FQ2tGb1pWH8u6XVsr12QaZy9fWp9IzEw050bPFH6IU42NX9b1b6z79zsaBnaU9baX
zZ1VmS6bn27vzG+vbuqqlcuezz+gYaCnrLcjXTO/pLq8cm66fWsq9Wj+NSM1PZWamRo9jFtnfZiprR2Kjd6tsWE0r/UuHHl4
+PimzvInduyetr93W2xMal5rn3jx7sH0jzWXR/pPn7s0f0nvm7FxbyGvGzhR279L3/Hnzw1X6vvPF/rj0J+Evhz6OuiboPdA
74N+Avo56JfH7wkT+k3QH4BeAr0G+kror0LfAv0d6J9APwl9EPpv4/cJDvRboM+AXgZ9IfTV0GPo26DvgX4Y+inoF6H/MX6f
GEC/DfpD0MuhL4b+AvT10HdA/wD6EeinoV+Cfm38XqSg3wX9EegV0JdCz0DfAP1t6AegH4d+FvpP43czAX0SdPDfBP9N8N8E
/03w3wT/TfDfBP9N8N8E/03w3wL/LfDfAv8t8N8C/y3w3wL/LfDfAv8t8N8C/y3w3wL/bfDfBv9t8N8G/23w3wb/bfDfBv9t
8N8G/23w3wb/bfBfgP8C/BfgvwD/BfgvwH8B/gvwX4D/AvwX4L8A/wX474D/DvjvgP8O+O+A/w7474D/DvjvgP8O+O+A/w74
74L/Lvjvgv8u+O+C/y7474L/Lvjvgv8u+O+C/y7474L/Hvjvgf8e+O+B/x7474H/Hvjvgf8e+O+B/x7474H/Hvjvg/8++O+D
/z7474P/Pvjvg/8++O+D/z7474P/Pvjvg/8B+B+A/wH4H4D/AfgfgP8B+B+A/wH4H4D/AfgfgP8B+B+C/yH4H4L/Ifgfgv8h
+B+C/yH4H4L/Ifgfgv8h+B+B/xH4H4H/Efgfgf8R+B+B/xH4H4H/Efgfgf8R+B+B/xL8l+C/BP8l+C/Bfwn+S/Bfgv8S/Jfg
vwT/JfgvwX8F/ivwX4H/CvxX4L8C/xX4r8B/Bf4r8F+B/wr8V+C/Bv81+K/Bfw3+a/Bfg/8a/Nfgvwb/NfivwX8N/mvwPwn+
J8H/JPifBP+T4/k/OlxvGsomM4UJsgGT5Rug3wP9MejzoNdDXwu9E3o39EPQB6B/C30EJshF0CdDvx96MfRq6M9AfwX6Zui7
oX8M/Qvo56H/ChNkAX0K9BT0NPSnoK+C3gz9DejvQv8M+lfQL0D/HSbIPvSp0B+EPhv6IuhroLdC3w59L/R+6F9D/wH6XzBZ
ltDvhD4T+hzoS6A3Qe+AvhP6fujHoH8DfRgmyOC/Cf6b4L8J/pvgvwn+m+C/Cf6b4L8J/pvgvwn+m+C/Bf5b4L8F/lvgvwX+
W+C/Bf5b4L8F/lvgvwX+W+C/Bf7b4L8N/tvgvw3+2+C/Df7b4L8N/tvgvw3+2+C/Df7b4L8A/wX4L8B/Af4L8F+A/wL8F+C/
AP8F+C/AfwH+C/DfAf8d8N8B/x3w3wH/HfDfAf8d8N8B/x3w3wH/HfDfBf9d8N8F/13w3wX/XfDfBf9d8N8F/13w3wX/XfDf
Bf898N8D/z3w3wP/PfDfA/898N8D/z3w3wP/PfDfA/898N8H/33w3wf/ffDfB/998N8H/33w3wf/ffDfB/998N8H/wPwPwD/
A/A/AP8D8D8A/wPwPwD/A/A/AP8D8D8A/wPwPwT/Q/A/BP9D8D8E/0PwPwT/Q/A/BP9D8D8E/0PwPwL/I/A/Av8j8D8C/yPw
PwL/I/A/Av8j8D8C/yPwPwL/JfgvwX8J/kvwX4L/EvyX4L8E/yX4L8F/Cf5L8F+C/wr8V+C/Av8V+K/AfwX+K/Bfgf8K/Ffg
vwL/FfivwH8N/mvwX4P/GvzX4L8G/zX4r8F/Df5r8F+D/xr81+B/EvxPgv9J8D8J/ifB/2QTTN4PD2WnXaPBe3dXT2H0bvz3
TGZXq3Hy7yWFPxr05V4/GBsfvXY1TlzOJtpG4om5zQezycxYmfz9SFxUKNOujZXU4nz5B+N9DvEJMQAA
"""
    }

    private fun wav(declaredSeconds: Int, actualSeconds: Int): ByteArray =
        ByteBuffer.allocate(44 + 16_000 * actualSeconds * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16_000 * declaredSeconds * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(16_000 * declaredSeconds * 2)
            repeat(16_000 * actualSeconds) { putShort(if (it % 40 < 20) 1000 else -1000) }
        }.array()
}
