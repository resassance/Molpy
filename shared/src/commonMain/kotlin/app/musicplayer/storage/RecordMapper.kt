package app.musicplayer.storage

import app.musicplayer.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit app-owned serialization preserves artist order and optional provider relationships. */
internal object RecordMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun track(track: Track, id: TrackId, cachedAtMs: Long) = TrackRecord(
        id.value, 1, track.title,
        json.encodeToString(track.artists.map { NamedSourceFields(it.name, it.source?.fields()) }),
        track.album?.let { json.encodeToString(NamedSourceFields(it.name, it.source?.fields())) },
        track.artwork.kind.name, track.artwork.remoteUrl, track.artwork.localKey, track.durationMs,
        track.provenance.source.provider, track.provenance.source.kind, track.provenance.source.id,
        track.provenance.fetchedAtMs, track.provenance.tentative, cachedAtMs,
    )

    fun track(record: TrackRecord, identities: List<ExternalIdentityRecord>, candidates: List<CandidateRecord>): Track {
        require(record.recordVersion == 1) { "Unsupported app track record version" }
        val artists = json.decodeFromString<List<NamedSourceFields>>(record.artistsJson).map { ArtistRef(it.name, it.source?.source()) }
        val album = record.albumJson?.let { json.decodeFromString<NamedSourceFields>(it) }?.let { AlbumRef(it.name, it.source?.source()) }
        return Track(TrackId(record.id), record.title, artists, album,
            Artwork(ArtworkKind.valueOf(record.artworkKind), record.artworkUrl, record.artworkLocalKey), record.durationMs,
            identities.map { SourceRef(it.provider, it.kind, it.sourceId) }.toSet(),
            MetadataProvenance(SourceRef(record.provenanceProvider, record.provenanceKind, record.provenanceId), record.fetchedAtMs, record.tentative),
            candidates.map { PlaybackCandidate(CandidateId(it.candidateId), SourceRef(it.provider, it.kind, it.sourceId), it.version, it.durationMs, MatchConfidence.valueOf(it.confidence)) })
    }

    fun candidates(track: Track, id: TrackId): List<CandidateRecord> = track.candidates.mapIndexed { index, candidate ->
        CandidateRecord(id.value, candidate.id.value, index, candidate.source.provider, candidate.source.kind,
            candidate.source.id, candidate.version, candidate.durationMs, candidate.confidence.name)
    }

    fun lyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long): LyricsRecord {
        val (kind, payload, quality) = when (lookup) {
            is LyricsLookup.Available -> Triple("AVAILABLE", json.encodeToString(LyricsFields(
                lookup.lyrics.plainText, lookup.lyrics.lines.map { LineFields(it.text, it.startMs, it.endMs) },
                lookup.lyrics.kind.name, lookup.lyrics.source.fields())), if (lookup.lyrics.kind == LyricsKind.LINE_SYNCED) 3 else 2)
            is LyricsLookup.Instrumental -> Triple("INSTRUMENTAL", json.encodeToString(lookup.source.fields()), 1)
            LyricsLookup.Unavailable -> Triple("UNAVAILABLE", null, 0)
            is LyricsLookup.Failed -> error("Transient failures are never stored")
        }
        return LyricsRecord(id.value, candidateId.value, 1, kind, payload, quality, atMs)
    }

    fun lyrics(record: LyricsRecord): LyricsLookup {
        require(record.recordVersion == 1) { "Unsupported app lyrics record version" }
        return when (record.kind) {
            "AVAILABLE" -> json.decodeFromString<LyricsFields>(requireNotNull(record.payloadJson)).let {
                LyricsLookup.Available(NormalizedLyrics(it.plainText, it.lines.map { line -> LyricLine(line.text, line.startMs, line.endMs) }, LyricsKind.valueOf(it.kind), it.source.source()))
            }
            "INSTRUMENTAL" -> LyricsLookup.Instrumental(json.decodeFromString<SourceFields>(requireNotNull(record.payloadJson)).source())
            "UNAVAILABLE" -> LyricsLookup.Unavailable
            else -> error("Unsupported app lyrics record kind")
        }
    }

    fun trackIds(ids: List<TrackId>): String = json.encodeToString(ids.map { it.value })
    fun trackIds(payload: String): List<TrackId> = json.decodeFromString<List<String>>(payload).map(::TrackId)

    fun network(config: NetworkConfig): String = json.encodeToString(NetworkFields(
        1, config.mode.name, config.endpoint?.host, config.endpoint?.port, config.credentialRef?.value))

    fun network(payload: String): NetworkConfig = json.decodeFromString<NetworkFields>(payload).let {
        require(it.recordVersion == 1) { "Unsupported network settings record version" }
        NetworkConfig(NetworkMode.valueOf(it.mode), it.host?.let { host -> ProxyEndpoint(host, requireNotNull(it.port)) }, it.credentialRef?.let(::SecretId))
    }

    private fun SourceRef.fields() = SourceFields(provider, kind, id)
    @Serializable private data class SourceFields(val provider: String, val kind: String, val id: String) {
        fun source() = SourceRef(provider, kind, id)
    }
    @Serializable private data class NamedSourceFields(val name: String, val source: SourceFields?)
    @Serializable private data class LineFields(val text: String, val startMs: Long, val endMs: Long?)
    @Serializable private data class LyricsFields(val plainText: String?, val lines: List<LineFields>, val kind: String, val source: SourceFields)
    @Serializable private data class NetworkFields(val recordVersion: Int, val mode: String, val host: String?, val port: Int?, val credentialRef: String?)
}
