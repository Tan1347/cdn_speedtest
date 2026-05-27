package org.tan.cdntest

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import android.os.StatFs
import java.io.File

object DownloadHelper {

    private const val SYSTEM_SUBDIR = "cdntest"

    fun getDownloadDir(context: Context): File {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        val useSystem = prefs.getBoolean("use_system_dir", false)
        return if (useSystem) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SYSTEM_SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            dir
        } else {
            val dir = File(context.getExternalFilesDir(null), "download")
            if (!dir.exists()) dir.mkdirs()
            dir
        }
    }

    fun isSystemDir(context: Context): Boolean {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("use_system_dir", false)
    }

    fun setUseSystemDir(context: Context, useSystem: Boolean) {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_system_dir", useSystem).apply()
    }

    fun getTsFormat(context: Context): TsOutputFormat {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("ts_format", TsOutputFormat.ORIGINAL.name) ?: TsOutputFormat.ORIGINAL.name
        return try { TsOutputFormat.valueOf(name) } catch (_: Exception) { TsOutputFormat.ORIGINAL }
    }

    fun setTsFormat(context: Context, format: TsOutputFormat) {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("ts_format", format.name).apply()
    }

    fun getDownloadThreads(context: Context): Int {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("download_threads", 3).coerceIn(1, 8)
    }

    fun setDownloadThreads(context: Context, threads: Int) {
        val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("download_threads", threads.coerceIn(1, 8)).apply()
    }

    fun getDownloadedFiles(context: Context): List<File> {
        val dir = getDownloadDir(context)
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getSystemDownloadManagerFiles(context: Context): List<SystemDownload> {
        val results = mutableListOf<SystemDownload>()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        val cursor = dm.query(query)
        cursor?.use {
            val idIdx = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleIdx = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val uriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val sizeIdx = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val dateIdx = it.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                val title = it.getString(titleIdx) ?: "未知"
                val localUri = it.getString(uriIdx) ?: ""
                val size = it.getLong(sizeIdx)
                val date = it.getLong(dateIdx)
                results.add(SystemDownload(id, title, localUri, size, date))
            }
        }
        return results.sortedByDescending { it.date }
    }

    data class StorageInfo(val total: Long, val available: Long, val used: Long)

    fun getStorageInfo(context: Context): StorageInfo {
        val dir = getDownloadDir(context)
        val stat = StatFs(dir.absolutePath)
        val total = stat.totalBytes
        val available = stat.availableBytes
        return StorageInfo(total, available, total - available)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }

    fun installApk(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "安装包文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val apkType = "application/vnd.android.package-archive"

        // 方法1: 直接 ACTION_VIEW（大多数设备可直接处理）
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, apkType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(viewIntent)
            return
        } catch (_: Exception) {}

        // 方法2: 尝试已知的系统安装器组件（适配各厂商 ROM）
        val installerPackages = listOf(
            "com.miui.packageinstaller" to "com.miui.packageinstaller.ui.InstallStart",
            "com.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
            "com.google.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
            "com.huawei.packageinstaller" to "com.huawei.packageinstaller.ui.InstallStart",
            "com.samsung.android.packageinstaller" to "com.samsung.android.packageinstaller.ui.InstallStart",
            "com.coloros.packageinstaller" to "com.coloros.packageinstaller.PackageInstallerActivity",
            "com.vivo.packageinstaller" to "com.vivo.packageinstaller.ui.InstallStart",
            "com.sec.android.app.samsungapps" to "com.sec.android.app.samsungapps.main.InstallAppActivity"
        )

        for ((pkg, cls) in installerPackages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, apkType)
                    setClassName(pkg, cls)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // 方法3: 使用 ACTION_INSTALL_PACKAGE
        try {
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = contentUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            context.startActivity(installIntent)
            return
        } catch (_: Exception) {}

        // 方法4: 最终回退 - 使用 ACTION_VIEW 不指定 MIME 类型
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                data = contentUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开安装器，请手动安装: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}

data class SystemDownload(
    val id: Long,
    val title: String,
    val localUri: String,
    val size: Long,
    val date: Long
)
