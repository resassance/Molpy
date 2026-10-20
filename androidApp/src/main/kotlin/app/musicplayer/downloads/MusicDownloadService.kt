package app.musicplayer.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.musicplayer.MusicApplication

/** User-initiated dataSync foreground service. No boot receiver or second scheduler. */
@UnstableApi
class MusicDownloadService : DownloadService(NOTIFICATION_ID, 1000L) {
    private val store get() = (application as MusicApplication).graph.downloadStore
    override fun onCreate() {
        super.onCreate()
        store.serviceStarted()
    }
    override fun onDestroy() {
        // Revoke admission before Media3 detaches. The application still owns manager/cache.
        store.serviceStopped()
        super.onDestroy()
    }
    override fun getDownloadManager(): DownloadManager = store.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Music downloads", NotificationManager.IMPORTANCE_LOW))
        }
        store.progress(downloads)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val active = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING }
        val progress = active?.percentDownloaded?.takeIf { it >= 0 }?.toInt()
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Music downloads")
            .setContentText(if (notMetRequirements != 0) "Waiting for network" else "Downloading music")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress ?: 0, progress == null).build()
    }
    // Media3 1.11.1 stops itself on the platform dataSync timeout. Stop active IO first.
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.serviceTimedOut()
        super.onTimeout(startId, fgsType)
    }
    private companion object {
        const val NOTIFICATION_ID = 1401
        const val CHANNEL = "music-downloads"
    }
}
