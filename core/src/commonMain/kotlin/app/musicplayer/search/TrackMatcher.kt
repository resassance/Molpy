package app.musicplayer.search

import app.musicplayer.model.Track
import kotlin.math.abs

/** Version tokens use TrackNormalizer's canonical vocabulary. */
data class SearchIntent(val query: String, val requestedVersions: Set<String> = emptySet())
data class MatchDecision(val track: Track, val autoLink: Boolean, val reasons: Set<String>)

class TrackMatcher(private val normalizer: TrackNormalizer = TrackNormalizer()) {
    fun rank(reference: Track, candidates: List<Track>, intent: SearchIntent): List<MatchDecision> {
        val requested = intent.requestedVersions.flatMap { version ->
            normalizer.explicitVersionMarkers(version).ifEmpty { setOf("unknown:${version.lowercase()}") }
        }.toSet()
        val referenceVersions = versions(reference)
        val expected = referenceVersions.ifEmpty { requested }
        val intentCompatible = referenceVersions.isEmpty() || requested.isEmpty() || referenceVersions == requested
        val evidence = candidates.sortedBy { it.id.value }.distinctBy(::sourceKey).map { candidate ->
            assess(reference, candidate, expected, intentCompatible)
        }.sortedWith(compareByDescending<Evidence> { it.score }.thenBy { it.track.id.value })
        val bestEligible = evidence.filter { it.eligible }.maxOfOrNull { it.score }
        val unambiguous = bestEligible != null && evidence.count { it.eligible && it.score == bestEligible } == 1
        return evidence.map {
            val autoLink = unambiguous && it.eligible && it.score == bestEligible
            MatchDecision(it.track, autoLink, it.reasons + if (it.eligible && !unambiguous) setOf("ambiguous") else emptySet())
        }
    }

    private fun assess(reference: Track, candidate: Track, expected: Set<String>, intentCompatible: Boolean): Evidence {
        val title = normalizer.normalizeTitle(reference.title)
        val candidateTitle = normalizer.normalizeTitle(candidate.title)
        val artist = primaryArtist(reference)
        val candidateArtist = primaryArtist(candidate)
        val titleExact = title.isNotEmpty() && title == candidateTitle
        val artistExact = artist.isNotEmpty() && artist == candidateArtist
        val versionCompatible = intentCompatible && expected == versions(candidate) && knownVersions(reference) && knownVersions(candidate) &&
            explicitDetails(reference) == explicitDetails(candidate)
        val duration = reference.durationMs
        val candidateDuration = candidate.durationMs
        val durationCompatible = duration != null && duration > 0 && candidateDuration != null && candidateDuration > 0 &&
            abs(duration - candidateDuration).toDouble() <= maxOf(2000.0, duration.toDouble() * 0.02)
        val album = reference.album?.name?.let(normalizer::normalizeArtist)?.takeIf { it.isNotEmpty() }
        val candidateAlbum = candidate.album?.name?.let(normalizer::normalizeArtist)?.takeIf { it.isNotEmpty() }
        val albumContradiction = album != null && candidateAlbum != null && album != candidateAlbum
        val catalog = candidate.provenance.source.provider == "youtube_music" && candidate.provenance.source.kind == "song"
        val official = candidate.title.contains("official", ignoreCase = true) ||
            candidate.artists.any { it.name.endsWith("- Topic", ignoreCase = true) }
        val score = (if (titleExact) 1000 else overlap(title, candidateTitle) * 40) +
            (if (artistExact) 1000 else overlap(artist, candidateArtist) * 40) +
            (if (versionCompatible) 500 else -1000) + (if (durationCompatible) 200 else 0) +
            (if (albumContradiction) -300 else if (album != null && candidateAlbum == album) 40 else 0) +
            (if (catalog) 20 else 0) + (if (official) 5 else 0)
        val reasons = linkedSetOf(
            if (titleExact) "title:exact" else "title:partial",
            if (artistExact) "artist:exact" else "artist:partial",
            if (versionCompatible) "version:compatible" else "version:incompatible-or-unknown",
            if (durationCompatible) "duration:within-tolerance" else "duration:missing-or-outside-tolerance",
            if (albumContradiction) "album:contradiction" else "album:compatible-or-unknown",
        )
        if (catalog) reasons += "catalog:music"
        if (official) reasons += "source:official-or-topic-hint"
        return Evidence(candidate, score, titleExact && artistExact && versionCompatible && durationCompatible && !albumContradiction, reasons)
    }

    private fun primaryArtist(track: Track): String = normalizer.normalizeArtist(track.artists.firstOrNull()?.name.orEmpty())
    private fun versions(track: Track): Set<String> = normalizer.versionMarkers(track.title) +
        track.candidates.flatMap { normalizer.explicitVersionMarkers(it.version) }
    private fun explicitDetails(track: Track): Set<String> = track.candidates.filter {
        normalizer.explicitVersionMarkers(it.version).isNotEmpty()
    }.map { normalizer.normalizeTitle("(${it.version})") }.filter { it.isNotEmpty() }.toSet()
    internal fun knownVersions(track: Track): Boolean = track.candidates.all {
        it.version.isBlank() || it.version.equals("studio", ignoreCase = true) || normalizer.explicitVersionMarkers(it.version).isNotEmpty()
    }
    private fun sourceKey(track: Track): String = track.candidates.map { "${it.source.provider}/${it.source.kind}/${it.source.id}" }
        .sorted().joinToString("|").ifEmpty { "track:${track.id.value}" }
    private fun overlap(a: String, b: String): Int {
        val left = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val right = b.split(' ').filter { it.isNotEmpty() }.toSet()
        return if (left.isEmpty() || right.isEmpty()) 0 else left.intersect(right).size * 10 / left.union(right).size
    }
    private data class Evidence(val track: Track, val score: Int, val eligible: Boolean, val reasons: Set<String>)
}
