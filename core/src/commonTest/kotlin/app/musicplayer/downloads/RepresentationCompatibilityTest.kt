package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlin.test.*

class RepresentationCompatibilityTest {
    private val source = SourceRef("youtube", "video", "one")
    private val original = Representation("140", "audio/mp4", "mp4a.40.2", 10000, null, "\"etag\"")

    private fun compare(actual: Representation, actualSource: SourceRef = source) =
        representationCompatibility(source, original, actualSource, actual)

    @Test
    fun optionalDurationDoesNotChangeFile() {
        assertEquals(
            RepresentationCompatibility.COMPATIBLE,
            compare(original.copy(expectedDurationMs = 188000)),
        )
    }

    @Test
    fun knownIdentityChangesAreRejected() {
        for (other in
            listOf(
                original.copy(id = "251"),
                original.copy(expectedBytes = 12000),
                original.copy(validator = "\"changed\""),
                original.copy(codec = "opus"),
            )) {
            assertEquals(RepresentationCompatibility.RESOURCE_CHANGED, compare(other))
        }
        assertEquals(
            RepresentationCompatibility.RESOURCE_CHANGED,
            compare(original, source.copy(id = "two")),
        )
    }

    @Test
    fun equivalentMimeAndCodecSpellingIsAccepted() {
        assertEquals(
            RepresentationCompatibility.COMPATIBLE,
            compare(
                original.copy(mimeType = " Audio/MP4 ; codecs=\"mp4a.40.2\"", codec = " MP4A.40.2 ")
            ),
        )
    }

    @Test
    fun unknownOptionalFieldsDoNotProveAChangeOrPermitResume() {
        assertEquals(
            RepresentationCompatibility.COMPATIBLE,
            compare(original.copy(expectedBytes = null, validator = null)),
        )
        assertEquals(
            ResumeDecision.RESTART,
            ResumePolicy()
                .decide(
                    ResourceStamp(source, "140", null, 10000),
                    ResourceStamp(source, "140", null, 10000),
                    1000,
                    ResumeResponse(206, 1000, 10000, null),
                ),
        )
    }

    @Test
    fun explicitNonAudioRepresentationIsUnsupported() {
        assertEquals(
            RepresentationCompatibility.UNSUPPORTED,
            compare(original.copy(mimeType = "text/html")),
        )
    }
}
