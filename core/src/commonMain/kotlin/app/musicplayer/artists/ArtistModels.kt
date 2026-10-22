package app.musicplayer.artists

import app.musicplayer.model.*

@JvmInline
value class ArtistId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class ArtistAliasKey(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class ArtistProfile(val id: ArtistId, val name: String, val source: SourceRef?) {
    init {
        require(name.isNotBlank())
    }
}

data class ArtistCredit(
    val name: String,
    val id: ArtistId?,
    val source: SourceRef?,
    val alias: ArtistAliasKey,
)

data class TrackPresentation(val base: Track, val artists: List<ArtistCredit>, val revision: Long)

data class ArtistMerge(
    val origin: ArtistId,
    val target: ArtistId,
    val preferredSource: SourceRef?,
    val revision: Long,
)

data class ArtistDirectory(
    val revision: Long = 0,
    val profiles: Map<ArtistId, ArtistProfile> = emptyMap(),
    val aliases: Map<ArtistAliasKey, ArtistId> = emptyMap(),
    val overrides: Map<TrackId, List<ArtistId>> = emptyMap(),
    val merges: Map<ArtistId, ArtistMerge> = emptyMap(),
)

data class ArtistCatalogHit(val name: String, val source: SourceRef, val artwork: Artwork?)

sealed interface ArtistChoice {
    data class Existing(val id: ArtistId) : ArtistChoice

    class Name(name: String) : ArtistChoice {
        val name = name.trim().also { require(it.length in 1..200) }

        override fun equals(other: Any?) = other is Name && name == other.name

        override fun hashCode() = name.hashCode()
    }

    data class Catalog(val profile: ArtistCatalogHit) : ArtistChoice
}

enum class ArtistWriteResult {
    APPLIED,
    STALE,
}

data class ArtistMergePreview(
    val origin: ArtistId,
    val target: ArtistId,
    val scopeKey: String,
    val trackCount: Int,
    val playedMs: Long,
    val preferredSource: SourceRef?,
)

data class ArtistSplitPreview(
    val origin: ArtistId,
    val scopeKey: String,
    val detached: List<ArtistProfile>,
    val trackCount: Int,
)

data class ArtistSessionSnapshot(
    val id: String,
    val trackId: TrackId,
    val artists: List<ArtistId>,
    val playedMs: Long?,
    val lastPlayedAtMs: Long,
)
