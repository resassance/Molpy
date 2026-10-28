package app.musicplayer.downloads

import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import app.musicplayer.network.forMediaTransfers
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Closed safe exception vocabulary. Never attach the HTTP exception as a cause. */
internal class DownloadFailure(val error: AppError) : IOException(error.code.name)

/** Runtime-only response boundary, invoked before OkHttpDataSource can skip/read any body bytes. */
internal data class DownloadResponse(
    val asset: OfflineAsset,
    val representation: Representation,
    val offset: Long,
    val length: Long,
    val status: Int,
    val headers: Map<String, List<String>>,
) {
    override fun toString(): String =
        "DownloadResponse(status=$status, offset=$offset, headers=redacted)"
}

internal fun interface DownloadResponseInspector {
    fun inspect(response: DownloadResponse): AppResult<Unit>
}

/** Fail-closed default for standalone callers; the application owner supplies DownloadRecovery. */
internal val FreshDownloadOnly = DownloadResponseInspector { response ->
    if (response.offset == 0L && response.status == 200) AppResult.Success(Unit)
    else AppResult.Failure(AppError(ErrorCode.RESOURCE_CHANGED, "download.response", false))
}

/**
 * Install INSIDE CacheDataSource.Factory as upstream, so exo_redir can only see stable identity.
 */
@UnstableApi
internal class DownloadDataSource private constructor(private val factory: Factory) : DataSource {
    private var stableUri: Uri? = null
    private var upstream: DataSource? = null
    private var trace: DownloadTrace? = null
    // close() deliberately does not clear rejection: CacheWriter retries bounded opens unbounded.
    @Volatile private var rejection: AppError? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Download IO cannot run on Main" }
        rejection?.let { throw DownloadFailure(it) }
        stableUri = dataSpec.uri
        try {
            val id = StableCacheIdentity.assetId(dataSpec.uri.toString())
            val asset =
                runBlocking { factory.repository.findAsset(id) }
                    ?: reject(ErrorCode.INCOMPLETE_MEDIA)
            trace = DownloadTrace("download.transfer", asset.source.id, factory.diagnostics)
            trace?.mark("open")
            if (dataSpec.key != asset.storageKey) reject(ErrorCode.RESOURCE_CHANGED)
            val attempt = factory.recovery?.attempt(asset)
            attempt?.rejection?.let {
                rejection = it
                throw DownloadFailure(it)
            }
            if (
                factory.recovery == null &&
                    factory.inspector === FreshDownloadOnly &&
                    dataSpec.position != 0L
            )
                reject(ErrorCode.RESOURCE_CHANGED)
            trace?.mark("resolve")
            val lease =
                when (val resolved = runBlocking { factory.resolve(asset) }) {
                    is AppResult.Success -> resolved.value
                    is AppResult.Failure -> throw DownloadFailure(resolved.error)
                }
            trace?.mark("lease")
            if (
                lease.transport != TransportKind.PROGRESSIVE ||
                    !isProgressiveAudio(lease.representation.mimeType)
            )
                reject(ErrorCode.UNSUPPORTED_TRANSPORT)
            if (
                lease.candidateId != asset.candidateId ||
                    representationCompatibility(
                        asset.source,
                        asset.representation,
                        asset.source,
                        lease.representation,
                    ) != RepresentationCompatibility.COMPATIBLE
            ) {
                val error = AppError(ErrorCode.RESOURCE_CHANGED, "download.representation", false)
                if (attempt != null) factory.recovery?.reject(asset, attempt, true, error)
                reject(ErrorCode.RESOURCE_CHANGED)
            }
            val runtimeUri = Uri.parse(lease.request.uri)
            if (
                runtimeUri.scheme !in setOf("https", "http") ||
                    runtimeUri.host.isNullOrBlank() ||
                    runtimeUri.userInfo != null
            )
                reject(ErrorCode.URL_REJECTED)
            // Production extractor admits HTTPS. Cleartext is useful only for loopback debug
            // fixtures.
            if (runtimeUri.scheme == "http" && runtimeUri.host != "127.0.0.1")
                reject(ErrorCode.URL_REJECTED)
            val calls = factory.callFactoryFor(lease.networkGeneration)
            val total =
                lease.representation.expectedBytes
                    ?: asset.representation.expectedBytes
                    ?: run {
                        trace?.mark("probe")
                        probeLength(calls, lease).also { trace?.mark("probe_end") }
                    }
            val remaining = total?.let { it - dataSpec.position }
            if (remaining != null && remaining <= 0) reject(ErrorCode.INCOMPLETE_MEDIA)
            val requestLength =
                if (remaining == null) dataSpec.length
                else if (dataSpec.length < 0) remaining else minOf(dataSpec.length, remaining)
            val downloadUri =
                Uri.parse(
                    downloadRangeUri(lease.request.uri, dataSpec.position, requestLength, total)
                )
            val mediaCalls = calls.forMediaTransfers()
            val inspectedCalls = Call.Factory { request ->
                ResponseInspectingCall(mediaCalls.newCall(request)) { response ->
                    trace?.mark("response", status = response.code)
                    val observed =
                        DownloadResponse(
                            asset,
                            lease.representation.copy(expectedBytes = total),
                            dataSpec.position,
                            dataSpec.length,
                            response.code,
                            response.headers.toMultimap(),
                        )
                    val result =
                        if (attempt != null)
                            checkNotNull(factory.recovery).inspect(observed, attempt)
                        else factory.inspector.inspect(observed)
                    if (result is AppResult.Failure) {
                        rejection = result.error
                        response.close()
                        throw DownloadFailure(result.error)
                    }
                    response
                }
            }
            val source = OkHttpDataSource.Factory(inspectedCalls).createDataSource()
            upstream = source
            // Transfer listeners see only stable requests. No runtime DataSpec is forwarded to
            // them.
            listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
            trace?.mark("connect")
            val length =
                source.open(
                    dataSpec
                        .buildUpon()
                        .setUri(downloadUri)
                        .setLength(requestLength)
                        .setHttpRequestHeaders(
                            lease.request.headers.filterKeys {
                                !it.equals("Proxy-Authorization", true) &&
                                    !it.equals("Accept-Encoding", true)
                            } + ("Accept-Encoding" to "identity")
                        )
                        .build()
                )
            trace?.mark("connected")
            listeners.forEach { it.onTransferStart(this, dataSpec, true) }
            openedSpec = dataSpec
            return length
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (failure: DownloadFailure) {
            trace?.mark("failed", failure.error.code)
            close()
            throw failure
        } catch (failure: Exception) {
            trace?.mark("failed", downloadError(failure, "download.open").code)
            close()
            throw DownloadFailure(downloadError(failure, "download.open"))
        }
    }

    private var openedSpec: DataSpec? = null

    private fun probeLength(calls: Call.Factory, lease: ResolvedPlayback): Long? {
        val probe = downloadRangeUri(lease.request.uri, 0, -1, 1)
        if (probe == lease.request.uri) return null
        val request = okhttp3.Request.Builder().url(lease.request.uri)
        lease.request.headers
            .filterKeys {
                !it.equals("Proxy-Authorization", true) &&
                    !it.equals("Range", true) &&
                    !it.equals("Accept-Encoding", true)
            }
            .forEach { (key, value) -> request.header(key, value) }
        request.header("Range", "bytes=0-0").header("Accept-Encoding", "identity")
        return try {
            calls.newCall(request.build()).execute().use { response ->
                trace?.mark("probe_response", status = response.code)
                if (
                    response.header("Content-Encoding")?.let { !it.equals("identity", true) } ==
                        true
                )
                    null
                else
                    downloadProbeLength(
                        response.code,
                        response.header("Content-Range"),
                        response.header("Content-Length"),
                    )
            }
        } catch (_: IOException) {
            trace?.mark("probe_failed", ErrorCode.NETWORK)
            null
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            val count = checkNotNull(upstream).read(buffer, offset, length)
            trace?.bytes(count)
            if (count > 0)
                openedSpec?.let { spec ->
                    listeners.forEach { it.onBytesTransferred(this, spec, true, count) }
                }
            count
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            trace?.mark("failed", downloadError(failure, "download.read").code)
            throw DownloadFailure(downloadError(failure, "download.read"))
        }

    override fun getUri(): Uri? = stableUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun close() {
        trace?.close()
        try {
            upstream?.close()
        } catch (_: IOException) {
            /* No raw runtime exception escapes. */
        }
        upstream = null
        openedSpec?.let { spec -> listeners.forEach { it.onTransferEnd(this, spec, true) } }
        openedSpec = null
        stableUri = null
    }

    private fun reject(code: ErrorCode): Nothing {
        val error = AppError(code, "download.open", false)
        rejection = error
        throw DownloadFailure(error)
    }

    class Factory(
        internal val repository: OfflineRepository,
        internal val resolve: suspend (OfflineAsset) -> AppResult<ResolvedPlayback>,
        internal val callFactoryFor: (Long) -> Call.Factory,
        internal val inspector: DownloadResponseInspector = FreshDownloadOnly,
        internal val recovery: DownloadRecovery? = null,
        internal val diagnostics: (DiagnosticEvent) -> Unit = {},
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DownloadDataSource(this)
    }
}

internal fun isProgressiveAudio(mime: String): Boolean {
    val type = mime.substringBefore(';').trim().lowercase()
    return type.startsWith("audio/") && type !in setOf("audio/mpegurl", "audio/x-mpegurl")
}

/** The inspection happens before the response reaches Media3, including its async HTTP path. */
private class ResponseInspectingCall(
    private val delegate: Call,
    private val inspect: (Response) -> Response,
) : Call by delegate {
    override fun execute(): Response {
        val response = delegate.execute()
        return try {
            inspect(response)
        } catch (failure: Exception) {
            response.close()
            throw failure
        }
    }

    override fun enqueue(responseCallback: Callback) =
        delegate.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    responseCallback.onFailure(call, e)

                override fun onResponse(call: Call, response: Response) {
                    val checked =
                        try {
                            inspect(response)
                        } catch (failure: Exception) {
                            response.close()
                            responseCallback.onFailure(
                                call,
                                DownloadFailure(downloadError(failure, "download.response")),
                            )
                            return
                        }
                    responseCallback.onResponse(call, checked)
                }
            }
        )

    override fun clone(): Call = ResponseInspectingCall(delegate.clone(), inspect)
}
