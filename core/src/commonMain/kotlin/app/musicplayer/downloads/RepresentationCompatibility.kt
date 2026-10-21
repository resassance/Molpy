package app.musicplayer.downloads

import app.musicplayer.model.*

enum class RepresentationCompatibility {
    COMPATIBLE,
    RESOURCE_CHANGED,
    UNSUPPORTED,
}

fun representationCompatibility(
    expectedSource: SourceRef,
    expected: Representation,
    actualSource: SourceRef,
    actual: Representation,
): RepresentationCompatibility {
    fun mime(value: String) = value.substringBefore(';').trim().lowercase()
    fun codecs(value: String) =
        value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted()
    val actualMime = mime(actual.mimeType)
    if (!actualMime.startsWith("audio/") || actualMime in setOf("audio/mpegurl", "audio/x-mpegurl"))
        return RepresentationCompatibility.UNSUPPORTED
    if (
        expectedSource != actualSource ||
            expected.id != actual.id ||
            mime(expected.mimeType) != actualMime ||
            (expected.codec.isNotBlank() &&
                actual.codec.isNotBlank() &&
                codecs(expected.codec) != codecs(actual.codec)) ||
            (expected.expectedBytes != null &&
                actual.expectedBytes != null &&
                expected.expectedBytes != actual.expectedBytes) ||
            (expected.validator != null &&
                actual.validator != null &&
                expected.validator != actual.validator)
    )
        return RepresentationCompatibility.RESOURCE_CHANGED
    return RepresentationCompatibility.COMPATIBLE
}
