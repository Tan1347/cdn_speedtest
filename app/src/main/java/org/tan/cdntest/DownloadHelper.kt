package org.tan.cdntest

import android.content.Context
import android.os.Environment
import java.io.File

object DownloadHelper {

    fun getDownloadDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "download")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDownloadedFiles(context: Context): List<File> {
        val dir = getDownloadDir(context)
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
