package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class ArtistArtworkMapperTest {
    private val source = SourceRef("youtube", "artist", "UC-one")

    @Test
    fun validMusicHeaderSelectsLargestPhoto() {
        assertEquals(
            Artwork(ArtworkKind.RELEASE, "https://img.example/large", null),
            ArtistArtworkMapper.map(artistPhotoFixture(), source).valueOrNull(),
        )
    }

    @Test
    fun validHeaderWithoutPhotoConfirmsAbsence() {
        assertEquals(
            AppResult.Success(null),
            ArtistArtworkMapper.map(artistPhotoFixture(photo = false), source),
        )
    }

    @Test
    fun malformedAndNonMusicHeadersAreProviderChanges() {
        for (body in
            listOf(
                "{",
                "{}",
                artistPhotoFixture()
                    .replace("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer"),
                artistPhotoFixture()
                    .replace("musicImmersiveHeaderRenderer", "c4TabbedHeaderRenderer"),
                artistPhotoFixture().replace("Confirmed Artist", " "),
            )) {
            assertEquals(
                ErrorCode.PROVIDER_CHANGED,
                ArtistArtworkMapper.map(body, source).errorOrNull()?.code,
                body,
            )
        }
    }

    @Test
    fun sourceMustBeConfirmedYoutubeArtist() {
        for (invalid in
            listOf(
                source.copy(provider = "other"),
                source.copy(kind = "album"),
            )) {
            assertEquals(
                ErrorCode.SOURCE_UNAVAILABLE,
                ArtistArtworkMapper.map(artistPhotoFixture(), invalid).errorOrNull()?.code,
            )
        }
    }

    @Test
    fun blankSourceIsRejectedBeforeProviderBoundary() {
        assertFailsWith<IllegalArgumentException> { source.copy(id = " ") }
    }

    @Test
    fun photoFixturesHaveValidMusicHeaderJson() {
        for (photo in listOf(false, true)) {
            val root = Json.parseToJsonElement(artistPhotoFixture(photo)).jsonObject
            val header =
                root
                    .getValue("header")
                    .jsonObject
                    .getValue("musicImmersiveHeaderRenderer")
                    .jsonObject
            assertEquals(
                "Confirmed Artist",
                header
                    .getValue("title")
                    .jsonObject
                    .getValue("runs")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(photo, "thumbnail" in header)
        }
    }

    @Test
    fun malformedDiscographyIsNotParsedForPhoto() {
        val body = artistPhotoFixture().dropLast(1) + " ,\"contents\":42}"
        assertEquals(
            "https://img.example/large",
            ArtistArtworkMapper.map(body, source).valueOrNull()?.remoteUrl,
        )
    }
}

internal fun artistPhotoFixture(photo: Boolean = true): String = buildJsonObject {
    putJsonObject("header") {
        putJsonObject("musicImmersiveHeaderRenderer") {
            putJsonObject("title") {
                putJsonArray("runs") {
                    addJsonObject { put("text", "Confirmed Artist") }
                }
            }
            if (photo) {
                putJsonObject("thumbnail") {
                    putJsonObject("musicThumbnailRenderer") {
                        putJsonObject("thumbnail") {
                            putJsonArray("thumbnails") {
                                addJsonObject {
                                    put("url", "https://img.example/small")
                                    put("width", 64)
                                    put("height", 64)
                                }
                                addJsonObject {
                                    put("url", "https://img.example/large")
                                    put("width", 512)
                                    put("height", 512)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
    .toString()
