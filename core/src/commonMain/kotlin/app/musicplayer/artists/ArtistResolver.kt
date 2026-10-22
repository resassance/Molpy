package app.musicplayer.artists

import app.musicplayer.model.*

object ArtistResolver {
    fun alias(track: Track, index: Int): ArtistAliasKey {
        val artist = track.artists[index]
        return ArtistAliasKey(
            artist.source?.let(::sourceKey)
                ?: "credit/${part(track.id.value)}/$index/${part(normalizedName(artist.name))}"
        )
    }

    fun root(id: ArtistId, directory: ArtistDirectory): ArtistId {
        var current = id
        val visited = mutableSetOf<ArtistId>()
        while (true) {
            require(current in directory.profiles) { "Missing artist identity" }
            require(visited.add(current)) { "Artist merge cycle" }
            val edge = directory.merges[current] ?: return current
            require(edge.origin == current) { "Invalid artist merge origin" }
            current = edge.target
        }
    }

    fun profile(id: ArtistId, directory: ArtistDirectory): ArtistProfile {
        val target = root(id, directory)
        val base = directory.profiles.getValue(target)
        val edges =
            directory.merges.values
                .filter { root(it.origin, directory) == target }
                .sortedByDescending { it.revision }
        val sources =
            (listOf(base.source) + edges.map { directory.profiles.getValue(it.origin).source })
                .filterNotNull()
                .toSet()
        val preferred = edges.firstNotNullOfOrNull { it.preferredSource?.takeIf(sources::contains) }
        return base.copy(source = preferred ?: base.source ?: sources.firstOrNull())
    }

    fun present(track: Track, directory: ArtistDirectory): TrackPresentation {
        val override = directory.overrides[track.id]
        require(override == null || override.isNotEmpty()) { "Empty artist override" }
        val credits =
            override?.map { id ->
                val artist = profile(id, directory)
                ArtistCredit(
                    artist.name,
                    artist.id,
                    artist.source,
                    ArtistAliasKey("local/${part(id.value)}"),
                )
            }
                ?: track.artists.mapIndexed { index, original ->
                    val key = alias(track, index)
                    val id = directory.aliases[key]
                    if (id == null) ArtistCredit(original.name, null, original.source, key)
                    else profile(id, directory).let { ArtistCredit(it.name, it.id, it.source, key) }
                }
        return TrackPresentation(
            track,
            credits.distinctBy {
                it.id?.let { id -> "id/${part(id.value)}" } ?: "alias/${part(it.alias.value)}"
            },
            directory.revision,
        )
    }

    fun normalizedName(name: String): String =
        name.trim().replace(Regex("[\\s\\p{Z}]+"), " ").lowercase()

    fun sourceKey(source: SourceRef): String =
        "source/${part(source.provider)}/${part(source.kind)}/${part(source.id)}"

    private fun part(value: String) = "${value.length}:$value"
}
