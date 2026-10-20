package app.musicplayer.playback

import app.musicplayer.model.*
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Closed diagnostic vocabulary: never forward upstream messages, bodies, clients or token values. */
enum class ResolverStage { RESOLVE, NETWORK, TOKEN, TRANSPORT, EXPIRY }
enum class ResolverReason {
    INVALID_CONTEXT, NETWORK_FAILURE, TOKEN_REQUIRED, UNSUPPORTED_TRANSPORT,
    SOURCE_UNAVAILABLE, UPSTREAM_FAILURE, INVALID_RESPONSE, EXPIRED,
}
enum class ResolverVersion { INNERTUBEX_0_10_0 }
data class ResolverDiagnostic(
    val stage: ResolverStage,
    val reason: ResolverReason,
    val version: ResolverVersion = ResolverVersion.INNERTUBEX_0_10_0,
)

/** Resolves one current candidate. No queue pre-resolution, account discovery or persistent URLs. */
class InnerTubePlaybackResolver(
    private val extractor: StreamExtractor,
    private val nowMs: () -> Long,
    private val currentNetworkGeneration: () -> Long,
    private val diagnostics: (ResolverDiagnostic) -> Unit = {},
) : PlaybackResolver {
    override suspend fun resolve(
        candidate: PlaybackCandidate,
        capabilities: PlaybackCapabilities,
        network: NetworkContext,
    ): AppResult<ResolvedPlayback> {
        if (!network.ready || currentNetworkGeneration() != network.generation) {
            return failure(ErrorCode.INVALID_CONFIG, ResolverStage.NETWORK, ResolverReason.INVALID_CONTEXT)
        }
        if (!network.online) return failure(ErrorCode.NETWORK, ResolverStage.NETWORK, ResolverReason.NETWORK_FAILURE, true)
        if (candidate.source.provider != "youtube" || candidate.source.kind != "video") {
            return failure(ErrorCode.SOURCE_UNAVAILABLE, ResolverStage.RESOLVE, ResolverReason.SOURCE_UNAVAILABLE)
        }
        val supported = capabilities.transports.intersect(setOf(TransportKind.PROGRESSIVE, TransportKind.HLS))
        if (supported.isEmpty()) return unsupported()
        val hints = ContentHints().withStreamCapabilities(
            allowHls = TransportKind.HLS in supported,
            allowSabr = false,
            allowBoundedRange = false,
        )
        return try {
            // A stale extracted URL gets exactly one fresh extraction, never an unbounded retry loop.
            repeat(2) {
                val extracted = extractor.extract(candidate.source.id, hints)
                if (currentNetworkGeneration() != network.generation) {
                    return failure(ErrorCode.INVALID_CONFIG, ResolverStage.NETWORK, ResolverReason.INVALID_CONTEXT)
                }
                if (extracted == null) {
                    return failure(ErrorCode.SOURCE_UNAVAILABLE, ResolverStage.RESOLVE, ResolverReason.SOURCE_UNAVAILABLE)
                }
                val transport = transport(extracted) ?: return unsupported()
                if (transport !in supported) return unsupported()
                if (extracted.videoId != candidate.source.id || !validMediaRequest(extracted)) {
                    return failure(ErrorCode.PROVIDER_CHANGED, ResolverStage.RESOLVE, ResolverReason.INVALID_RESPONSE)
                }
                val expiry = extracted.expiresAt?.toEpochMilliseconds()
                if (expiry == null || hasLeaseLifetime(expiry, nowMs())) {
                    val mediaDuration = extracted.mediaMetadata?.durationSeconds
                        ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
                    return AppResult.Success(ResolvedPlayback(
                        candidate.id,
                        Representation(extracted.itag.toString(), extracted.mimeType!!,
                            extracted.codecs.orEmpty(), extracted.contentLengthBytes, mediaDuration, null),
                        transport,
                        MediaRequest(extracted.audioUrl, extracted.headers),
                        expiry,
                        network.generation,
                    ))
                }
            }
            failure(ErrorCode.URL_REJECTED, ResolverStage.EXPIRY, ResolverReason.EXPIRED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            val exhaustedTokenPath = e.reason == StreamResolveException.Reason.NO_PLAYABLE_STREAM ||
                e.reason == StreamResolveException.Reason.EXPLICIT_UNSUPPORTED
            if (exhaustedTokenPath && e.diagnostics?.attempts?.any { it.outcome == "po_token_unavailable" } == true) {
                failure(ErrorCode.SOURCE_UNAVAILABLE, ResolverStage.TOKEN, ResolverReason.TOKEN_REQUIRED)
            } else {
                when (e.reason) {
                    StreamResolveException.Reason.NETWORK -> failure(ErrorCode.NETWORK, ResolverStage.NETWORK, ResolverReason.NETWORK_FAILURE, true)
                    StreamResolveException.Reason.AGE_RESTRICTED -> failure(ErrorCode.LOGIN_REQUIRED, ResolverStage.RESOLVE, ResolverReason.SOURCE_UNAVAILABLE)
                    StreamResolveException.Reason.UNKNOWN -> failure(ErrorCode.PROVIDER_CHANGED, ResolverStage.RESOLVE, ResolverReason.UPSTREAM_FAILURE)
                    else -> failure(ErrorCode.SOURCE_UNAVAILABLE, ResolverStage.RESOLVE, ResolverReason.SOURCE_UNAVAILABLE)
                }
            }
        } catch (e: AppFailureException) {
            AppResult.Failure(e.error)
        } catch (_: IOException) {
            failure(ErrorCode.NETWORK, ResolverStage.NETWORK, ResolverReason.NETWORK_FAILURE, true)
        } catch (_: Exception) {
            failure(ErrorCode.UNKNOWN, ResolverStage.RESOLVE, ResolverReason.UPSTREAM_FAILURE)
        }
    }

    private fun transport(stream: ExtractedStream): TransportKind? = when {
        stream.sabrBootstrap != null || stream.audioUrl.startsWith("sabr:", ignoreCase = true) -> null
        stream.requireBoundedRange || stream.useRangeChunks -> null // Task6 must verify the reader first.
        stream.mimeType?.substringBefore(';')?.lowercase() in HLS_TYPES -> TransportKind.HLS
        else -> TransportKind.PROGRESSIVE
    }

    private fun validMediaRequest(stream: ExtractedStream): Boolean {
        val mime = stream.mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (!mime.startsWith("audio/") && mime !in HLS_TYPES) return false
        if (stream.contentLengthBytes?.let { it < 0 } == true) return false
        val uri = runCatching { Url(stream.audioUrl) }.getOrNull() ?: return false
        return uri.protocol.name == "https" && uri.host.isNotBlank() && uri.user.isNullOrEmpty() && uri.password.isNullOrEmpty()
    }

    private fun unsupported() = failure(ErrorCode.UNSUPPORTED_TRANSPORT, ResolverStage.TRANSPORT, ResolverReason.UNSUPPORTED_TRANSPORT)

    private fun failure(code: ErrorCode, stage: ResolverStage, reason: ResolverReason, retryable: Boolean = false): AppResult.Failure {
        diagnostics(ResolverDiagnostic(stage, reason))
        return AppResult.Failure(AppError(code, "playback.resolve", retryable))
    }

    private companion object {
        val HLS_TYPES = setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl")
    }
}
