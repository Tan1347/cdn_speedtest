package org.tan.cdntest

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class ReleaseInfo(
    val tagName: String,
    val body: String,
    val apkUrl: String,
    val apkSize: Long
)

class UpdateChecker(private val activity: Activity) {

    companion object {
        private const val GITHUB_API =
            "https://api.github.com/repos/Tan1347/cdn_speedtest/releases/latest"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var isDownloading = false

    fun getCurrentVersion(): String {
        return try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    fun checkForUpdate(onResult: (ReleaseInfo?) -> Unit) {
        executor.execute {
            try {
                val url = URL(GITHUB_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "CDNViewer-Android")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    val obj = JSONObject(json)
                    val tagName = obj.getString("tag_name")
                    val body = obj.optString("body", "")

                    val assets = obj.getJSONArray("assets")
                    var apkUrl = ""
                    var apkSize = 0L
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }

                    conn.disconnect()

                    if (apkUrl.isNotEmpty()) {
                        val release = ReleaseInfo(tagName, body, apkUrl, apkSize)
                        val currentVersion = getCurrentVersion()
                        if (tagName != currentVersion) {
                            mainHandler.post { onResult(release) }
                        } else {
                            mainHandler.post { onResult(null) }
                        }
                    } else {
                        mainHandler.post { onResult(null) }
                    }
                } else {
                    conn.disconnect()
                    mainHandler.post { onResult(null) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(null) }
            }
        }
    }

    fun showUpdateDialog(release: ReleaseInfo) {
        val sizeStr = if (release.apkSize > 0) {
            String.format("%.1f MB", release.apkSize / 1024.0 / 1024.0)
        } else ""

        val message = buildString {
            append("发现新版本: ${release.tagName}\n")
            append("当前版本: ${getCurrentVersion()}\n")
            if (sizeStr.isNotEmpty()) append("大小: $sizeStr\n")
            if (release.body.isNotBlank()) append("\n更新内容:\n${release.body}")
        }

        AlertDialog.Builder(activity)
            .setTitle("更新提示")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ -> startDownload(release.apkUrl, release.apkSize) }
            .setNegativeButton("稍后", null)
            .setCancelable(true)
            .show()
    }

    private fun startDownload(apkUrl: String, totalSize: Long) {
        if (isDownloading) return
        isDownloading = true

        showProgressDialog()

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "speedtest-update-${System.currentTimeMillis()}.apk"
        val downloadDir = DownloadHelper.getDownloadDir(activity)
        val destFile = File(downloadDir, fileName)
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("网速测试 更新")
            .setDescription("正在下载新版本...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destFile))
            .setMimeType("application/vnd.android.package-archive")

        downloadId = dm.enqueue(request)

        pollProgress(dm, totalSize)
        registerDownloadComplete(dm)
    }

    private fun showProgressDialog() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.tvProgress)

        progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在下载更新")
            .setView(view)
            .setNegativeButton("后台下载") { _, _ ->
                progressDialog = null
            }
            .setCancelable(false)
            .show()
    }

    private fun pollProgress(dm: DownloadManager, totalSize: Long) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val runnable = object : Runnable {
            override fun run() {
                if (!isDownloading) return
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    cursor.close()

                    val total = if (bytesTotal > 0) bytesTotal else totalSize
                    val progress = if (total > 0) (bytesDownloaded * 100 / total).toInt() else 0

                    mainHandler.post {
                        progressBar?.progress = progress
                        val downloadedMB = bytesDownloaded / 1024.0 / 1024.0
                        val totalMB = total / 1024.0 / 1024.0
                        progressText?.text = String.format("%.1f MB / %.1f MB (%d%%)", downloadedMB, totalMB, progress)
                    }

                    if (progress < 100) {
                        mainHandler.postDelayed(this, 500)
                    }
                } else {
                    cursor?.close()
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
        mainHandler.postDelayed(runnable, 500)
    }

    private fun registerDownloadComplete(dm: DownloadManager) {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    isDownloading = false
                    try {
                        activity.unregisterReceiver(this)
                    } catch (_: Exception) {}
                    downloadReceiver = null

                    mainHandler.post {
                        progressDialog?.dismiss()
                        progressDialog = null
                        showInstallDialog(dm.getUriForDownloadedFile(downloadId))
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            activity.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun showInstallDialog(apkUri: Uri) {
        AlertDialog.Builder(activity)
            .setTitle("下载完成")
            .setMessage("新版本已下载完成，是否立即安装？")
            .setPositiveButton("立即安装") { _, _ -> installApk(apkUri) }
            .setNegativeButton("稍后安装", null)
            .setCancelable(true)
            .show()
    }

    private fun installApk(uri: Uri) {
        val contentUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            File(DownloadHelper.getDownloadDir(activity), uri.lastPathSegment ?: "update.apk")
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
