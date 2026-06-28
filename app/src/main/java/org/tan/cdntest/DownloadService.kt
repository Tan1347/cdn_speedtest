package org.tan.cdntest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DownloadService : Service(), DownloadListener {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备下载...", 0, 0))
        DownloadEngine.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DownloadEngine.removeListener(this)
        super.onDestroy()
    }

    override fun onProgress(task: DownloadTask) {
        val percent = if (task.totalBytes > 0) (task.downloadedBytes * 100 / task.totalBytes).toInt() else 0
        val speedText = DownloadHelper.formatFileSize(task.speed) + "/s"
        val progressText = "${DownloadHelper.formatFileSize(task.downloadedBytes)} / ${DownloadHelper.formatFileSize(task.totalBytes)}  $speedText"

        val notification = buildNotification("${task.fileName}\n$progressText", percent, task.downloadedBytes)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onComplete(task: DownloadTask) {
        val notification = buildNotification("${task.fileName} 下载完成", 100, task.totalBytes)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)

        // Stop service if no more active downloads
        if (DownloadEngine.getAllTasks().none { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING }) {
            stopSelf()
        }
    }

    override fun onFailed(task: DownloadTask, error: String) {
        val notification = buildFailedNotification("${task.fileName} 下载失败: $error")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)

        if (DownloadEngine.getAllTasks().none { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING }) {
            stopSelf()
        }
    }

    override fun onCancelled(task: DownloadTask) {
        if (DownloadEngine.getAllTasks().none { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING }) {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载管理",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "文件下载进度通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, @Suppress("UNUSED_PARAMETER") totalBytes: Long): Notification {
        val intent = Intent(this, DownloadManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("下载中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildFailedNotification(text: String): Notification {
        val intent = Intent(this, DownloadManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载失败")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }
}
