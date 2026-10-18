package app.musicplayer.model

/** Durable provider identity; temporary network addresses belong to runtime resolution. */
data class SourceRef(val provider: String, val kind: String, val id: String) {
    init {
        require(provider.isNotBlank()) { "Provider must not be blank" }
        require(kind.isNotBlank()) { "Kind must not be blank" }
        require(id.isNotBlank()) { "Source ID must not be blank" }
        require(!id.trim().startsWith("//") && !URI_SCHEME.containsMatchIn(id.trim())) {
            "Source ID must not be a URL"
        }
    }

    private companion object {
        val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}

data class ArtistRef(val name: String, val source: SourceRef?)
data class AlbumRef(val name: String, val source: SourceRef?)
