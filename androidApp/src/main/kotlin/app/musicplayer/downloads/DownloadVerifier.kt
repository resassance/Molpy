package app.musicplayer.downloads

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/**
 * Caller MUST exclude all same-key writers/removal/reset for the entire verify-and-publish interval,
 * and correlate transportComplete with the current admitted stamp/attempt. An ID-only predicate is
 * not identity proof or a lock. This verifier returns evidence only; it never publishes AVAILABLE.
 */
@UnstableApi
internal class DownloadVerifier(
    private val cache: Cache,
    private val transportComplete: suspend (AssetId) -> Boolean,
) {
    suspend fun verify(asset: OfflineAsset): AppResult<Unit> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        try {
            val budget = Budget(context)
            budget.check()
            if (!transportComplete(asset.id)) invalid()
            val stamp = identity(asset)
            val expectedBytes = admittedExpectedBytes(asset.representation.expectedBytes, stamp.expectedBytes)
            val extent = snapshot(asset, expectedBytes)
            // Keep exactly Task 14's stable app URI until its approved helper is adopted in phase 2.
            val uri = "musicplayer-download://asset/" + URLEncoder.encode(asset.id.value, "UTF-8")
                .replace("+", "%20").replace("%7E", "~")
            val bridge = CacheMediaDataSource(cache, asset.storageKey, uri, extent, budget::check)
            val decoded: Long
            try {
                scanAllBytes(bridge, extent, budget)
                decoded = decodeAllAudio(bridge, asset, budget)
            } finally {
                bridge.close()
            }
            budget.check()
            if (bridge.hasFailure || identity(asset) != stamp || snapshot(asset, expectedBytes) != extent) invalid()
            val complete = transportComplete(asset.id)
            context.ensureActive()
            CompletionPolicy().verify(CompletionEvidence(
                expectedBytes = expectedBytes,
                storedBytes = extent,
                hasHoles = false,
                sourceDurationMs = asset.representation.expectedDurationMs
                    ?: asset.trackSnapshot.candidates.single { it.id == asset.candidateId }.durationMs
                    ?: asset.trackSnapshot.durationMs,
                decodedDurationMs = decoded,
                readable = true,
                transportComplete = complete,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            context.ensureActive()
            AppResult.Failure(AppError(ErrorCode.INCOMPLETE_MEDIA, "downloads.verify", true))
        }
    }

    private fun identity(asset: OfflineAsset): ResourceStamp {
        val candidate = asset.trackSnapshot.candidates.singleOrNull { it.id == asset.candidateId } ?: invalid()
        if (asset.trackId != asset.trackSnapshot.id || candidate.source != asset.source ||
            asset.storageKey != DownloadPolicy().keyFor(asset.trackId, asset.candidateId, asset.representation.id)) invalid()
        val stamp = ResourceStampMetadata.read(cache, asset.storageKey) ?: invalid()
        if (stamp.source != asset.source || stamp.representationId != asset.representation.id ||
            asset.representation.validator?.let { it != stamp.strongValidator } == true) invalid()
        return stamp
    }

    private fun snapshot(asset: OfflineAsset, expectedBytes: Long?): Long {
        var end = 0L
        var total = 0L
        val spans = cache.getCachedSpans(asset.storageKey)
        for (span in spans) {
            if (!span.isCached || span.position != end || span.length <= 0 || span.length > Long.MAX_VALUE - end ||
                span.length > Long.MAX_VALUE - total) invalid()
            end += span.length; total += span.length
        }
        if (end <= 0 || total != end || expectedBytes?.let { it != end } == true ||
            !cache.isCached(asset.storageKey, 0, end) || cache.getCachedBytes(asset.storageKey, 0, end) != end) invalid()
        val metadata = cache.getContentMetadata(asset.storageKey)
        if (metadata.contains(ContentMetadata.KEY_CONTENT_LENGTH)) {
            val length = ContentMetadata.getContentLength(metadata)
            if (length != -1L && length != end) invalid()
        }
        return end
    }

    private fun scanAllBytes(source: CacheMediaDataSource, extent: Long, budget: Budget) {
        val bytes = ByteArray(64 * 1024)
        var position = 0L
        while (position < extent) {
            budget.check()
            val read = source.readAt(position, bytes, 0, minOf(bytes.size.toLong(), extent - position).toInt())
            if (read <= 0) invalid()
            position += read
        }
        if (source.hasFailure) invalid()
    }

    private fun decodeAllAudio(source: CacheMediaDataSource, asset: OfflineAsset, budget: Budget): Long {
        val extractor = MediaExtractor()
        try {
            if (asset.representation.mimeType.substringBefore(';').trim().equals("audio/webm", ignoreCase = true)) {
                // Check only the bounded EBML header envelope. Android still parses/decodes all media.
                val header = ByteArray(minOf(source.getSize(), SAMPLE_CAPACITY.toLong()).toInt())
                var position = 0
                while (position < header.size) {
                    budget.check()
                    val read = source.readAt(position.toLong(), header, position, header.size - position)
                    if (read <= 0) invalid()
                    position += read
                }
                if (!hasWebmDocType(header)) invalid()
            }
            extractor.setDataSource(source)
            budget.check()
            if (extractor.psshInfo?.isNotEmpty() == true ||
                (Build.VERSION.SDK_INT >= 24 && extractor.drmInitData != null)) invalid()
            // Multiple tracks/alternate audio are outside the admitted progressive-audio scope.
            if (extractor.trackCount != 1) invalid()
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: invalid()
            if (!supportedAudioTrack(asset.representation.mimeType, mime) || format.containsKey("crypto-key") ||
                (format.containsKey("is-encrypted") && format.getInteger("is-encrypted") != 0)) invalid()
            rejectTrim(format)
            extractor.selectTrack(0)
            return if (mime == "audio/raw") scanPcm(extractor, format, source, budget)
                else decodeCompressed(extractor, format, budget)
        } finally {
            extractor.release()
        }
    }

    private fun scanPcm(extractor: MediaExtractor, format: MediaFormat, source: CacheMediaDataSource, budget: Budget): Long {
        // API 23 does not promise a PCM-encoding format key. Establish WAV PCM16 from actual fmt bytes.
        val wav = wavPcm16(source)
        val layout = layout(format, allowDefault16 = wav != null)
        if (wav != null && layout != wav) invalid()
        val frames = Frames(layout)
        val buffer = ByteBuffer.allocate(SAMPLE_CAPACITY)
        while (true) {
            budget.check()
            checkSample(extractor, buffer.capacity())
            buffer.clear()
            val count = extractor.readSampleData(buffer, 0)
            if (count < 0) break
            if (count == 0 || count > buffer.capacity()) invalid()
            frames.add(count)
            extractor.advance()
        }
        if (source.hasFailure) invalid()
        return frames.duration()
    }

    private fun decodeCompressed(extractor: MediaExtractor, format: MediaFormat, budget: Budget): Long {
        val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) ?: invalid()
        val codec = MediaCodec.createByCodecName(decoderName)
        var started = false
        try {
            codec.configure(format, null, null, 0)
            codec.start(); started = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var frames: Frames? = null
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                budget.check()
                var progressed = false
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_US)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: invalid()
                        input.clear()
                        checkSample(extractor, input.capacity())
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (count == 0 || count > input.capacity() || extractor.sampleTime < 0) invalid()
                            codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val next = layout(codec.outputFormat, allowDefault16 = true)
                        if (frames != null && frames.layout != next) invalid()
                        if (frames == null) frames = Frames(next)
                        progressed = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (index < 0) invalid()
                        try {
                            val next = layout(codec.getOutputFormat(index), allowDefault16 = true)
                            if (frames != null && frames.layout != next) invalid()
                            if (frames == null) frames = Frames(next)
                            if (info.size < 0 || info.offset < 0) invalid()
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(index) ?: invalid()
                                if (info.offset > output.capacity() || info.size > output.capacity() - info.offset) invalid()
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) frames.add(info.size)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        progressed = true
                    }
                }
                if (progressed) lastProgress = System.nanoTime()
                else if (System.nanoTime() - lastProgress > STALL_NS) invalid()
            }
            if (!inputEnded) invalid()
            return frames?.duration() ?: invalid()
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
    }

    private fun checkSample(extractor: MediaExtractor, capacity: Int) {
        if (extractor.sampleTrackIndex < 0) return
        if (extractor.sampleTrackIndex != 0 || extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() != 0) invalid()
        if (Build.VERSION.SDK_INT >= 28 && extractor.sampleSize > capacity.toLong()) invalid()
    }

    private data class Layout(val rate: Int, val channels: Int)

    private fun layout(format: MediaFormat, allowDefault16: Boolean): Layout {
        if (format.getString(MediaFormat.KEY_MIME) != "audio/raw") invalid()
        rejectTrim(format)
        if (Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            if (format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) invalid()
        } else if (!allowDefault16) invalid()
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (rate <= 0 || channels !in 1..8) invalid()
        return Layout(rate, channels)
    }

    private fun rejectTrim(format: MediaFormat) {
        // Conservative scope: priming/padding handling needs device evidence; never invent/double-trim PCM.
        if (Build.VERSION.SDK_INT >= 30 &&
            ((format.containsKey(MediaFormat.KEY_ENCODER_DELAY) && format.getInteger(MediaFormat.KEY_ENCODER_DELAY) != 0) ||
             (format.containsKey(MediaFormat.KEY_ENCODER_PADDING) && format.getInteger(MediaFormat.KEY_ENCODER_PADDING) != 0))) invalid()
    }

    private fun wavPcm16(source: CacheMediaDataSource): Layout? {
        fun bytes(position: Long, count: Int): ByteBuffer {
            val result = ByteArray(count)
            var read = 0
            while (read < count) {
                val step = source.readAt(position + read, result, read, count - read)
                if (step <= 0) invalid()
                read += step
            }
            return ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        }
        if (source.getSize() < 12) return null
        val header = bytes(0, 12)
        if (header.int != 0x46464952 || header.getInt(8) != 0x45564157) return null // RIFF / WAVE
        var position = 12L
        while (position <= source.getSize() - 8 && position < SAMPLE_CAPACITY) {
            val chunk = bytes(position, 8)
            val id = chunk.int
            val size = chunk.int.toLong() and 0xffffffffL
            if (id == 0x20746d66) { // fmt
                if (size < 16 || position > source.getSize() - 24) invalid()
                val fmt = bytes(position + 8, 16)
                if (fmt.short.toInt() != 1) invalid()
                val channels = fmt.short.toInt() and 0xffff
                val rate = fmt.int
                val byteRate = fmt.int.toLong() and 0xffffffffL
                val alignment = fmt.short.toInt() and 0xffff
                val bits = fmt.short.toInt() and 0xffff
                if (bits != 16 || channels !in 1..8 || rate <= 0 || alignment != channels * 2 ||
                    byteRate != rate.toLong() * alignment) invalid()
                return Layout(rate, channels)
            }
            val step = 8 + size + (size and 1)
            if (step > source.getSize() - position) invalid()
            position += step
        }
        invalid()
    }

    private class Frames(val layout: Layout) {
        private var count = 0L
        fun add(bytes: Int) {
            val alignment = layout.channels * 2
            if (bytes % alignment != 0 || bytes / alignment > Long.MAX_VALUE - count) invalid()
            count += bytes / alignment
        }
        fun duration(): Long {
            val seconds = count / layout.rate
            if (count <= 0 || seconds > Long.MAX_VALUE / 1000) invalid()
            val whole = seconds * 1000
            val fraction = (count % layout.rate) * 1000 / layout.rate
            if (fraction > Long.MAX_VALUE - whole || whole + fraction <= 0) invalid()
            return whole + fraction
        }
    }

    private class Budget(private val context: CoroutineContext) {
        private val start = System.nanoTime()
        private var operations = 0L
        @Synchronized fun check() {
            context.ensureActive()
            if (++operations > 10_000_000 || System.nanoTime() - start > TOTAL_NS) invalid()
        }
    }

    private companion object {
        const val SAMPLE_CAPACITY = 1024 * 1024
        const val DEQUEUE_US = 10_000L
        const val STALL_NS = 15_000_000_000L
        const val TOTAL_NS = 1_800_000_000_000L
        fun invalid(): Nothing = throw IOException("Incomplete local media")
    }
}
