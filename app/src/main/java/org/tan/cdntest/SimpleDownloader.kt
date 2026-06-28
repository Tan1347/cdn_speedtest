package org.tan.cdntest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class SimpleDownloader(private val activity: Activity) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var cancelled = false

    fun download(url: String, fileName: String, onComplete: ((File) -> Unit)? = null) {
        cancelled = false
        showProgressDialog(fileName)
        AppLogger.i(activity, "Downloader", "开始下载: $fileName, URL: $url")

        executor.execute {
            var response: okhttp3.Response? = null
            var file: File? = null
            try {
                val downloadDir = DownloadHelper.getDownloadDir(activity)
                file = File(downloadDir, fileName)

                val request = Request.Builder()
                    .url(url)
                    .build()

                response = HttpClient.client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("响应体为空")
                val totalSize = body.contentLength()
                AppLogger.i(activity, "Downloader", "连接成功, 文件大小: ${totalSize / 1024 / 1024}MB, 保存: ${file.absolutePath}")

                val input = body.byteStream()
                val output = FileOutputStream(file)
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var lastProgress = 0

                while (!cancelled) {
                    val bytes = input.read(buffer)
                    if (bytes == -1) break
                    output.write(buffer, 0, bytes)
                    downloaded += bytes

                    if (totalSize > 0) {
                        val progress = (downloaded * 100 / totalSize).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            mainHandler.post {
                                progressBar?.progress = progress
                                progressText?.text = String.format(
                                    "%.1f MB / %.1f MB (%d%%)",
                                    downloaded / 1024.0 / 1024.0,
                                    totalSize / 1024.0 / 1024.0,
                                    progress
                                )
                            }
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()

                if (!cancelled) {
                    AppLogger.i(activity, "Downloader", "下载完成: $fileName, 大小: ${downloaded / 1024}KB")
                    mainHandler.post {
                        progressDialog?.dismiss()
                        progressDialog = null
                        onComplete?.invoke(file)
                    }
                } else {
                    AppLogger.w(activity, "Downloader", "下载取消: $fileName")
                    file.delete()
                }
            } catch (e: Exception) {
                AppLogger.e(activity, "Downloader", "下载失败: $fileName, URL: $url", e)
                file?.delete()
                mainHandler.post {
                    progressDialog?.dismiss()
                    progressDialog = null
                    Toast.makeText(activity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                response?.close()
            }
        }
    }

    private fun showProgressDialog(fileName: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.tvProgress)
        progressText?.text = "准备下载..."

        progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在下载: $fileName")
            .setView(view)
            .setNegativeButton("取消") { _, _ ->
                cancelled = true
                progressDialog = null
            }
            .setCancelable(false)
            .show()
    }
}
