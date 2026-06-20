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
import org.json.JSONObject
import java.io.File
import okhttp3.Request
import java.util.concurrent.Executors

data class ReleaseInfo(
    val tagName: String,
    val body: String,
    val apkUrl: String,
    val apkSize: Long
)

class UpdateChecker(private val activity: Activity) {

    companion object {
        private const val REPO = "Tan1347/cdn_speedtest"
        private const val GITHUB_API = "https://api.github.com/repos/$REPO/releases/latest"
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
            AppLogger.i(activity, "UpdateChecker", "检查更新...")

            val client = AppHttpClient.get(activity)

            // 使用优选排序后的镜像列表
            val apiMirrors = GitHubHostsHelper.getSortedApiMirrors(activity)
            AppLogger.d(activity, "UpdateChecker", "镜像排序: $apiMirrors")

            for ((index, mirror) in apiMirrors.withIndex()) {
                try {
                    val apiUrl = "${mirror}${GITHUB_API}"
                    AppLogger.d(activity, "UpdateChecker", "尝试 API 源 $index: $apiUrl")

                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "CDNViewer-Android")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val json = response.body?.string() ?: ""
                        response.close()
                        val release = parseReleaseJson(json)
                        if (release != null) {
                            val currentVersion = getCurrentVersion()
                            val remoteVersion = extractVersion(release.tagName)
                            if (isNewerVersion(remoteVersion, currentVersion)) {
                                val optimized = optimizeDownloadUrl(release)
                                AppLogger.i(activity, "UpdateChecker", "发现新版本: ${release.tagName} (当前: $currentVersion)")
                                mainHandler.post { onResult(optimized) }
                            } else {
                                AppLogger.i(activity, "UpdateChecker", "已是最新版本: $currentVersion")
                                mainHandler.post { onResult(null) }
                            }
                            return@execute
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    AppLogger.w(activity, "UpdateChecker", "API 源 $index 失败: ${e.message}")
                }
            }

            // 所有镜像失败，尝试直连 GitHub（OkHttp 客户端已配置优选 DNS，自动使用优选 IP）
            AppLogger.w(activity, "UpdateChecker", "镜像全部失败，尝试优选 DNS 直连 GitHub...")
            val release = tryDirectWithOptimizedDns(client)
            if (release != null) {
                val currentVersion = getCurrentVersion()
                val remoteVersion = extractVersion(release.tagName)
                if (isNewerVersion(remoteVersion, currentVersion)) {
                    val optimized = optimizeDownloadUrl(release)
                    AppLogger.i(activity, "UpdateChecker", "优选 DNS 直连成功: ${release.tagName}")
                    mainHandler.post { onResult(optimized) }
                    return@execute
                } else {
                    mainHandler.post { onResult(null) }
                    return@execute
                }
            }

            AppLogger.e(activity, "UpdateChecker", "所有更新源均失败")
            mainHandler.post { onResult(null) }
        }
    }

    private fun parseReleaseJson(json: String): ReleaseInfo? {
        return try {
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

            if (apkUrl.isNotEmpty()) ReleaseInfo(tagName, body, apkUrl, apkSize)
            else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 tag name 中提取版本号
     * v1.0.2-abc1234 → 1.0.2
     * v1.0.2 → 1.0.2
     * 1.0.2 → 1.0.2
     */
    private fun extractVersion(tagName: String): String {
        // 去掉 v 前缀
        val noV = if (tagName.startsWith("v")) tagName.substring(1) else tagName
        // 去掉 - 后面的 commit hash 部分（如 -abc1234）
        val dashIndex = noV.indexOf('-')
        return if (dashIndex > 0) noV.substring(0, dashIndex) else noV
    }

    /**
     * 语义化版本比较: remote > current 返回 true
     * 1.1.0 > 1.0.5 → true
     * 1.0.5 > 1.1.0 → false
     * 1.0.5 > 1.0.5 → false
     */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * 将 GitHub 原始下载链接替换为镜像代理链接，提升国内下载成功率
     */
    private fun optimizeDownloadUrl(release: ReleaseInfo): ReleaseInfo {
        val originalUrl = release.apkUrl
        if (!originalUrl.contains("github.com") && !originalUrl.contains("githubusercontent.com")) {
            return release
        }
        // 使用优选排序后的第一个代理镜像
        val downloadMirrors = GitHubHostsHelper.getSortedDownloadMirrors(activity)
        val mirrorPrefix = downloadMirrors.firstOrNull() ?: "https://ghfast.top/"
        val proxiedUrl = "$mirrorPrefix$originalUrl"
        AppLogger.d(activity, "UpdateChecker", "下载链接代理: $proxiedUrl")
        return release.copy(apkUrl = proxiedUrl)
    }

    /**
     * 所有镜像失败时，通过 OkHttp 优选 DNS 直连 GitHub API。
     * GitHubDns 会自动使用优选 IP 解析 GitHub 域名，无需手动指定 IP。
     */
    private fun tryDirectWithOptimizedDns(client: okhttp3.OkHttpClient): ReleaseInfo? {
        return try {
            // 确保远程 hosts 已缓存（供 GitHubDns 使用）
            GitHubHostsHelper.fetchRemoteHosts(activity)

            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "CDNViewer-Android")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                response.close()
                AppLogger.i(activity, "UpdateChecker", "优选 DNS 直连成功")
                parseReleaseJson(json)
            } else {
                response.close()
                AppLogger.w(activity, "UpdateChecker", "优选 DNS 直连返回: ${response.code}")
                null
            }
        } catch (e: Exception) {
            AppLogger.w(activity, "UpdateChecker", "优选 DNS 直连失败: ${e.message}")
            null
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

    private var destFile: File? = null
    private var originalDownloadUrl: String = ""
    private var mirrorIndex: Int = 0
    private var downloadTotalSize: Long = 0

    private fun startDownload(apkUrl: String, totalSize: Long) {
        if (isDownloading) return
        isDownloading = true

        originalDownloadUrl = apkUrl
        mirrorIndex = 0
        downloadTotalSize = totalSize
        enqueueDownload(apkUrl, totalSize)
    }

    private fun enqueueDownload(apkUrl: String, totalSize: Long) {
        showProgressDialog()
        AppLogger.i(activity, "UpdateChecker", "开始下载更新: $apkUrl")

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "speedtest-update-${System.currentTimeMillis()}.apk"
        val downloadDir = DownloadHelper.getDownloadDir(activity)
        destFile = File(downloadDir, fileName)
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

    /**
     * 下载失败时尝试下一个镜像源
     */
    private fun retryWithNextMirror() {
        mirrorIndex++
        val downloadMirrors = GitHubHostsHelper.getSortedDownloadMirrors(activity)
        if (mirrorIndex < downloadMirrors.size) {
            val nextMirror = downloadMirrors[mirrorIndex]
            // 从原始 URL 中提取 github 路径部分
            val githubPath = originalDownloadUrl
                .replace("https://ghfast.top/", "")
                .replace("https://ghproxy.net/", "")
                .replace("https://github.moeyy.xyz/", "")
            val nextUrl = "$nextMirror$githubPath"
            AppLogger.w(activity, "UpdateChecker", "下载失败，切换到镜像源 $mirrorIndex: $nextUrl")
            mainHandler.post {
                progressText?.text = "下载失败，正在切换镜像源..."
            }
            // 短暂延迟后重试
            mainHandler.postDelayed({
                enqueueDownload(nextUrl, downloadTotalSize)
            }, 1500)
        } else {
            AppLogger.e(activity, "UpdateChecker", "所有下载镜像均失败")
            mainHandler.post {
                isDownloading = false
                progressDialog?.dismiss()
                progressDialog = null
                android.widget.Toast.makeText(activity, "下载失败，请检查网络后重试", android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    cursor.close()

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        isDownloading = false
                        mainHandler.post {
                            progressBar?.progress = 100
                            progressText?.text = "下载完成"
                            progressDialog?.dismiss()
                            progressDialog = null
                            showInstallDialog()
                        }
                        return
                    }

                    if (status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                        retryWithNextMirror()
                        return
                    }

                    val total = if (bytesTotal > 0) bytesTotal else totalSize
                    val progress = if (total > 0) (bytesDownloaded * 100 / total).toInt() else 0

                    mainHandler.post {
                        progressBar?.progress = progress
                        val downloadedMB = bytesDownloaded / 1024.0 / 1024.0
                        val totalMB = total / 1024.0 / 1024.0
                        progressText?.text = String.format("%.1f MB / %.1f MB (%d%%)", downloadedMB, totalMB, progress)
                    }

                    mainHandler.postDelayed(this, 500)
                } else {
                    cursor?.close()
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
        mainHandler.postDelayed(runnable, 500)
    }

    @Suppress("UNUSED_PARAMETER")
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

                    AppLogger.i(activity, "UpdateChecker", "更新下载完成")
                    mainHandler.post {
                        progressDialog?.dismiss()
                        progressDialog = null
                        showInstallDialog()
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

    private fun showInstallDialog() {
        AlertDialog.Builder(activity)
            .setTitle("下载完成")
            .setMessage("新版本已下载完成，是否立即安装？")
            .setPositiveButton("立即安装") { _, _ -> installApk() }
            .setNegativeButton("稍后安装", null)
            .setCancelable(true)
            .show()
    }

    private fun installApk() {
        val file = destFile ?: return
        DownloadHelper.installApk(activity, file)
    }
}
