package org.tan.cdntest

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class AppLogger(
    private val maxLogAgeDays: Int = 7,
    private val maxFileSizeMB: Long = 5,
    private val bufferSizeBytes: Int = 1024 * 1024,       // 1MB
    private val flushThresholdBytes: Int = 512 * 1024     // 500KB
) {

    enum class Level(val value: Int, val tag: String) {
        DEBUG(0, "D"),
        INFO(1, "I"),
        WARN(2, "W"),
        ERROR(3, "E")
    }

    companion object {
        private const val PREF_NAME = "logger_prefs"
        private const val KEY_ENABLED = "log_enabled"
        private const val KEY_LEVEL = "log_level"

        @Volatile
        private var instance: AppLogger? = null

        fun get(): AppLogger {
            return instance ?: synchronized(this) {
                instance ?: AppLogger().also { instance = it }
            }
        }

        fun init(context: Context) {
            get().start(context)
        }

        fun flush() {
            get().flushBuffer()
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun getLevel(context: Context): Level {
            val index = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_LEVEL, Level.INFO.value)
            return Level.entries.getOrElse(index) { Level.INFO }
        }

        fun setLevel(context: Context, level: Level) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LEVEL, level.value).apply()
        }

        fun d(context: Context, tag: String, msg: String) = get().log(context, Level.DEBUG, tag, msg)
        fun i(context: Context, tag: String, msg: String) = get().log(context, Level.INFO, tag, msg)
        fun w(context: Context, tag: String, msg: String) = get().log(context, Level.WARN, tag, msg)
        fun e(context: Context, tag: String, msg: String, tr: Throwable? = null) {
            val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
            get().log(context, Level.ERROR, tag, fullMsg)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileTimeFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var currentFileSize: Long = 0
    private var initialized = false

    // 内存缓冲区
    private val buffer = StringBuilder(bufferSizeBytes)
    @Volatile
    private var bufferBytes = 0

    fun start(context: Context) {
        if (initialized) return
        initialized = true

        executor.execute {
            val baseDir = File(context.getExternalFilesDir(null), "log")
            if (!baseDir.exists()) baseDir.mkdirs()
            logDir = baseDir

            cleanOldLogs(baseDir)
            compressOldLogs(baseDir)
            createNewLogFile(baseDir)

            // 定期刷新缓冲区（每30秒）
            while (true) {
                Thread.sleep(30_000)
                flushBuffer()
            }
        }
    }

    fun log(context: Context, level: Level, tag: String, msg: String) {
        if (!isEnabled(context)) return
        if (level.value < getLevel(context).value) return

        val time = timeFormat.format(Date())
        val line = "$time ${level.tag}/$tag: $msg"

        // 输出到 logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, msg)
            Level.INFO -> Log.i(tag, msg)
            Level.WARN -> Log.w(tag, msg)
            Level.ERROR -> Log.e(tag, msg)
        }

        // 写入内存缓冲区
        synchronized(buffer) {
            buffer.appendLine(line)
            bufferBytes += line.length + 1
            if (bufferBytes >= flushThresholdBytes) {
                flushBufferInternal()
            }
        }
    }

    private fun flushBuffer() {
        synchronized(buffer) {
            flushBufferInternal()
        }
    }

    private fun flushBufferInternal() {
        if (buffer.isEmpty()) return
        val data = buffer.toString()
        buffer.clear()
        bufferBytes = 0

        try {
            val file = currentLogFile ?: return
            file.appendText(data)
            currentFileSize += data.toByteArray(Charsets.UTF_8).size

            // 超过大小限制时创建新文件
            if (currentFileSize >= maxFileSizeMB * 1024 * 1024) {
                logDir?.let { createNewLogFile(it) }
            }
        } catch (_: Exception) {}
    }

    private fun createNewLogFile(baseDir: File) {
        val today = dateFormat.format(Date())
        val dateDir = File(baseDir, today)
        if (!dateDir.exists()) dateDir.mkdirs()

        val fileName = "log_${fileTimeFormat.format(Date())}.txt"
        currentLogFile = File(dateDir, fileName)
        currentFileSize = 0
        try {
            currentLogFile?.appendText("=== 日志开始 ${timeFormat.format(Date())} ===\n")
        } catch (_: Exception) {}
    }

    private fun cleanOldLogs(baseDir: File) {
        val cutoff = System.currentTimeMillis() - maxLogAgeDays * 24 * 60 * 60 * 1000L
        val dirs = baseDir.listFiles { f -> f.isDirectory } ?: return

        for (dir in dirs) {
            if (!dir.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue
            try {
                val dirDate = dateFormat.parse(dir.name)
                if (dirDate != null && dirDate.time < cutoff) {
                    dir.deleteRecursively()
                }
            } catch (_: Exception) {}
        }

        // 也清理超过天数的 .xz 压缩文件
        val xzFiles = baseDir.listFiles { f -> f.isFile && f.name.endsWith(".xz") } ?: return
        for (file in xzFiles) {
            val name = file.nameWithoutExtension
            if (!name.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue
            try {
                val fileDate = dateFormat.parse(name)
                if (fileDate != null && fileDate.time < cutoff) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }

    private fun compressOldLogs(baseDir: File) {
        val today = dateFormat.format(Date())
        val dirs = baseDir.listFiles { f -> f.isDirectory } ?: return

        for (dir in dirs) {
            val dirName = dir.name
            if (dirName == today || dirName.endsWith(".xz")) continue
            if (!dirName.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue

            val xzFile = File(baseDir, "$dirName.xz")
            if (xzFile.exists()) continue

            try {
                compressDirectory(dir, xzFile)
                dir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    private fun compressDirectory(dir: File, xzFile: File) {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return

        val output = XZOutputStream(BufferedOutputStream(FileOutputStream(xzFile)), LZMA2Options())
        output.use { out ->
            for (file in files) {
                if (!file.isFile) continue
                writeEntry(out, file.name, file)
            }
        }
    }

    private fun writeEntry(output: OutputStream, name: String, file: File) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val content = file.readBytes()

        output.write(intToBytes(nameBytes.size))
        output.write(nameBytes)
        output.write(longToBytes(content.size.toLong()))
        output.write(content)
    }

    private fun intToBytes(v: Int): ByteArray {
        return byteArrayOf(
            (v shr 24).toByte(),
            (v shr 16).toByte(),
            (v shr 8).toByte(),
            v.toByte()
        )
    }

    private fun longToBytes(v: Long): ByteArray {
        return byteArrayOf(
            (v shr 56).toByte(),
            (v shr 48).toByte(),
            (v shr 40).toByte(),
            (v shr 32).toByte(),
            (v shr 24).toByte(),
            (v shr 16).toByte(),
            (v shr 8).toByte(),
            v.toByte()
        )
    }
}
