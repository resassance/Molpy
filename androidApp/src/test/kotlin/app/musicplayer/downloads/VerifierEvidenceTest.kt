package app.musicplayer.downloads

import app.musicplayer.model.ErrorCode
import app.musicplayer.model.errorOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VerifierEvidenceTest {
    @Test fun stampKnownByteMismatchCannotDisappearBehindMatchingDurations() {
        val expected = admittedExpectedBytes(null, 320_045)
        val result = CompletionPolicy().verify(CompletionEvidence(expected, 320_044, false,
            10_000, 10_000, true, true))
        assertEquals(ErrorCode.INCOMPLETE_MEDIA, result.errorOrNull()?.code)
    }

    @Test fun matchingAdmittedLengthCanProveCompletionWithoutIndependentDuration() {
        val expected = admittedExpectedBytes(null, 320_044)
        assertNull(CompletionPolicy().verify(CompletionEvidence(expected, 320_044, false,
            null, 10_000, true, true)).errorOrNull())
    }

    @Test fun assetAndStampLengthsMustAgreeAndRemainPositive() {
        assertEquals(42L, admittedExpectedBytes(42, 42))
        assertNull(admittedExpectedBytes(null, null))
        listOf(42L to null, 42L to 43L, 0L to 0L, null to 0L, null to -1L).forEach { (asset, stamp) ->
            try { admittedExpectedBytes(asset, stamp); fail("Invalid admitted length: $asset / $stamp") }
            catch (_: IOException) { }
        }
    }

    @Test fun webmContainerMapsToExtractedOpusOrVorbisAudioMime() {
        listOf("audio/webm", "audio/webm; codecs=\"opus\"", " Audio/WebM ; codecs=vorbis").forEach { mime ->
            assertTrue(mime, supportedAudioTrack(mime, "audio/opus"))
            assertTrue(mime, supportedAudioTrack(mime, "audio/vorbis"))
        }
        listOf("audio/webm", "video/webm", "audio/raw", "audio/mp4a-latm").forEach { actual ->
            assertFalse(actual, supportedAudioTrack("audio/webm", actual))
        }
        assertFalse(supportedAudioTrack("video/webm", "audio/opus"))
        assertTrue(supportedAudioTrack("audio/mp4; codecs=mp4a.40.2", "audio/mp4a-latm"))
        assertFalse(supportedAudioTrack("audio/wav", "audio/opus"))
    }

    @Test fun actualWebmDocTypeIsRequiredInsteadOfContainerMimeAlone() {
        // Exact first 36 bytes of the retained FFmpeg-generated WebM/Opus device fixture.
        val generatedHeader = "1a45dfa39f4286810142f7810142f2810442f381084282847765626d4287810442858102"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(hasWebmDocType(generatedHeader))
        assertTrue(hasWebmDocType(ebml(docType("webm"))))
        assertFalse(hasWebmDocType(ebml(docType("matroska"))))
        assertFalse(hasWebmDocType("RIFF-WAVE-not-webm".toByteArray()))
        assertFalse(hasWebmDocType(ebml(byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 1))))
        assertFalse(hasWebmDocType(ebml(docType("webm") + docType("webm"))))
    }

    @Test fun ebmlEnvelopeAcceptsLegalVintSizesAndAdditionalHeaderFields() {
        val fields = byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 1) + docType("webm") +
            byteArrayOf(0x42, 0x85.toByte(), 0x81.toByte(), 2)
        assertTrue(hasWebmDocType(ebml(fields, twoByteSize = true) + byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)))
    }

    @Test fun malformedOrUnboundedEbmlHeadersFailClosed() {
        val valid = ebml(docType("webm"))
        for (length in 0 until valid.size) assertFalse("prefix $length", hasWebmDocType(valid.copyOf(length)))
        assertFalse(hasWebmDocType(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0xff.toByte())))
        assertFalse(hasWebmDocType(ebml(byteArrayOf(0, 0))))
        assertFalse(hasWebmDocType(ebml(byteArrayOf(0x42, 0x82.toByte(), 0xff.toByte()))))
    }

    private fun docType(value: String) = byteArrayOf(0x42, 0x82.toByte(), (0x80 or value.length).toByte()) + value.toByteArray()
    private fun ebml(fields: ByteArray, twoByteSize: Boolean = false): ByteArray =
        byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) +
            (if (twoByteSize) byteArrayOf(0x40, fields.size.toByte()) else byteArrayOf((0x80 or fields.size).toByte())) + fields
}
