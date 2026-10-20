package app.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.musicplayer.downloads.*
import app.musicplayer.model.*

/** Plain technical controls over the platform downloader's normalized projection. */
@Composable
fun DownloadsScreen(assets: List<OfflineAsset>, failures: Map<AssetId, AppError>, usage: StorageUsageSnapshot,
    onPause: (AssetId) -> Unit, onResume: (AssetId) -> Unit, onCancel: (AssetId) -> Unit,
    onDelete: (AssetId) -> Unit, onRemoveAll: () -> Unit, onRefresh: () -> Unit, onManageStorage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Загрузки", style = MaterialTheme.typography.titleMedium)
        Text("Занято аудио и обложками: ${usage.totalBytes} байт")
        Text("Загрузки хранятся в данных приложения и удаляются при удалении приложения или очистке его данных. Избранное и история сохраняются при удалении загрузок здесь.")
        Row {
            TextButton(onClick = onRefresh) { Text("Обновить размер") }
            TextButton(onClick = onRemoveAll, enabled = assets.isNotEmpty()) { Text("Удалить все загрузки") }
        }
        if (assets.isEmpty()) Text("Загрузок пока нет.")
        assets.forEach { asset ->
            Text(asset.trackSnapshot.title)
            Text("Состояние: ${when (asset.state) {
                DownloadState.QUEUED -> "В очереди"
                DownloadState.DOWNLOADING -> "Загружается"
                DownloadState.PAUSED -> "Приостановлена"
                DownloadState.AVAILABLE -> "Доступна офлайн"
                DownloadState.FAILED -> "Ошибка"
            }}")
            Text("Скачано: ${asset.bytes} байт; на устройстве: ${usage.perAssetBytes[asset.id] ?: 0} байт")
            asset.representation.expectedBytes?.takeIf { it > 0 }?.let { total ->
                val fraction = (asset.bytes.toDouble() / total).coerceIn(0.0, 1.0)
                Text("${(fraction * 100).toInt()}% из $total байт")
                LinearProgressIndicator(progress = { fraction.toFloat() })
            }
            failures[asset.id]?.let { error ->
                Text(present(error).message)
                if (error.code == ErrorCode.STORAGE_FULL) TextButton(onClick = onManageStorage) { Text("Освободить место") }
                if (error.code == ErrorCode.INCOMPATIBLE_RESOURCE) Text("Удалите эту загрузку и скачайте запись заново.")
            }
            Row {
                when (asset.state) {
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> TextButton(onClick = { onPause(asset.id) }) { Text("Пауза") }
                    DownloadState.PAUSED, DownloadState.FAILED -> TextButton(onClick = { onResume(asset.id) }) { Text("Повторить / продолжить") }
                    DownloadState.AVAILABLE -> Unit
                }
                if (asset.state == DownloadState.AVAILABLE || asset.state == DownloadState.FAILED) {
                    TextButton(onClick = { onDelete(asset.id) }) { Text("Удалить") }
                } else TextButton(onClick = { onCancel(asset.id) }) { Text("Отменить и удалить данные") }
            }
        }
    }
}
