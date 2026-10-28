package app.musicplayer.ui.downloads

import app.musicplayer.downloads.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.formatBytes
import kotlin.math.ceil
import kotlin.time.TimeSource

internal fun downloadClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

fun DownloadsState.hasDownload(trackId: TrackId): Boolean =
    assets.any { it.trackId == trackId } || preparations.any { it.track.id == trackId }

fun visiblePreparations(state: DownloadsState): List<DownloadPreparation> =
    state.preparations.filter { preparation ->
        state.assets.none {
            it.trackId == preparation.track.id &&
                it.candidateId == preparation.candidateId &&
                it.state != DownloadState.FAILED
        }
    }

fun visibleAssets(state: DownloadsState): List<OfflineAsset> =
    state.assets.filter { asset ->
        asset.state != DownloadState.FAILED ||
            state.preparations.none {
                it.track.id == asset.trackId && it.candidateId == asset.candidateId
            }
    }

data class DownloadStatus(
    val label: String,
    val progress: Float? = null,
    val indeterminate: Boolean = false,
)

fun downloadStatus(
    asset: OfflineAsset,
    activity: DownloadActivity?,
    rate: DownloadRate,
): DownloadStatus {
    when (asset.state) {
        DownloadState.AVAILABLE -> return DownloadStatus("На устройстве")
        DownloadState.FAILED -> return DownloadStatus("Не удалось скачать")
        DownloadState.PAUSED -> return DownloadStatus("На паузе · ${formatBytes(asset.bytes)}")
        else -> Unit
    }
    val phase =
        activity?.phase
            ?: if (asset.state == DownloadState.QUEUED) DownloadPhase.QUEUED
            else DownloadPhase.PREPARING
    return when (phase) {
        DownloadPhase.QUEUED -> DownloadStatus("В очереди")
        DownloadPhase.PREPARING -> DownloadStatus("Подготовка", indeterminate = true)
        DownloadPhase.VERIFYING -> DownloadStatus("Проверка файла", indeterminate = true)
        DownloadPhase.DOWNLOADING -> {
            val bytes = maxOf(asset.bytes, activity?.bytes ?: 0)
            val total =
                (activity?.totalBytes ?: asset.representation.expectedBytes)?.takeIf { it > 0 }
            val progress = total?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
            val label = buildList {
                add(
                    if (total == null) formatBytes(bytes)
                    else
                        "${formatBytes(bytes)} из ${formatBytes(total)} · ${(progress!! * 100).toInt()}%"
                )
                if (rate.waitingForData) add("Ожидание данных")
                else {
                    rate.bytesPerSecond?.let { add("${formatBytes(it)}/с") }
                    rate.remainingSeconds
                        ?.takeIf { it > 0 }
                        ?.let { add("около ${remainingTime(it)}") }
                }
            }
                .joinToString(" · ")
            DownloadStatus(label, progress, indeterminate = progress == null)
        }
    }
}

private fun remainingTime(seconds: Long): String =
    when {
        seconds < 60 -> "$seconds с"
        seconds < 3600 -> "${ceil(seconds.toDouble() / 60).toLong()} мин"
        else -> "${seconds / 3600} ч ${ceil((seconds % 3600).toDouble() / 60).toLong()} мин"
    }
