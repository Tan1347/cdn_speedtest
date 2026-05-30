package org.tan.cdntest

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class DownloadStatus {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

data class DownloadTask(
    val id: Long = System.currentTimeMillis(),
    val url: String,
    var fileName: String,
    var destPath: String,
    val mimeType: String? = null,
    var totalBytes: Long = 0,
    var downloadedBytes: Long = 0,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var speed: Long = 0,
    var date: Long = System.currentTimeMillis()
)

interface DownloadListener {
    fun onProgress(task: DownloadTask)
    fun onComplete(task: DownloadTask)
    fun onFailed(task: DownloadTask, error: String)
    fun onCancelled(task: DownloadTask)
}

object DownloadEngine {

    private const val BUFFER_SIZE = 8192
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val runningCount = AtomicInteger(0)
    private val pendingQueue = mutableListOf<String>()
    private val listeners = mutableListOf<DownloadListener>()
    private val pausedUrls = mutableSetOf<String>()
    private var appContext: Context? = null

    // M3U8 task metadata: url -> (m3u8Url, segments, keyInfo, outputFormat, outputName)
    data class M3u8TaskData(
        val m3u8Url: String,
        val segments: List<M3u8Segment>,
        val keyInfo: KeyInfo?,
        val outputFormat: TsOutputFormat,
        val outputName: String
    )
    private val m3u8Tasks = ConcurrentHashMap<String, M3u8TaskData>()
    fun isM3u8Task(url: String): Boolean = m3u8Tasks.containsKey(url)

    fun addListener(listener: DownloadListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DownloadListener) {
        listeners.remove(listener)
    }

    fun enqueue(context: Context, url: String, fileName: String, destPath: String, mimeType: String? = null): DownloadTask {
        if (appContext == null) appContext = context.applicationContext
        val task = DownloadTask(
            url = url,
            fileName = fileName,
            destPath = destPath,
            mimeType = mimeType
        )
        tasks[url] = task

        // Save to database
        scope.launch {
            DownloadRecordStore.add(context, DownloadRecord(
                name = fileName,
                url = url,
                path = destPath,
                size = 0,
                date = task.date
            ))
        }

        startDownload(context, task)
        return task
    }

    fun enqueueM3u8(
        context: Context,
        m3u8Url: String,
        outputName: String,
        format: TsOutputFormat,
        onParsed: ((segmentCount: Int) -> Unit)? = null
    ) {
        Log.i("CDNTest", "[enqueueM3u8] 开始入队: url=$m3u8Url, output=$outputName, format=${format.label}")
        if (appContext == null) appContext = context.applicationContext

        val task = DownloadTask(
            url = m3u8Url,
            fileName = outputName,
            destPath = "",
            status = DownloadStatus.RUNNING
        )
        tasks[m3u8Url] = task
        notifyProgress(task)

        // Start foreground service for M3U8 download
        try {
            val intent = Intent(context, DownloadService::class.java)
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w("CDNTest", "[enqueueM3u8] 启动前台服务失败: ${e.message}")
        }

        val job = scope.launch {
            try {
                // Phase 1: Parse M3U8
                Log.i("CDNTest", "[M3U8] Phase 1: 解析 M3U8 URL: $m3u8Url")
                val m3u8Info = M3u8Parser.fetchAndParse(m3u8Url)
                if (m3u8Info == null || m3u8Info.segments.isEmpty()) {
                    Log.e("CDNTest", "[M3U8] 解析失败: m3u8Info=${m3u8Info != null}, segments=${m3u8Info?.segments?.size ?: 0}")
                    task.status = DownloadStatus.FAILED
                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onFailed(task, "M3U8 解析失败") }
                    }
                    tasks.remove(m3u8Url)
                    return@launch
                }

                Log.i("CDNTest", "[M3U8] 解析成功: ${m3u8Info.segments.size} 个分片, key=${m3u8Info.keyInfo?.method ?: "无"}")
                task.totalBytes = m3u8Info.segments.size.toLong()
                m3u8Tasks[m3u8Url] = M3u8TaskData(m3u8Url, m3u8Info.segments, m3u8Info.keyInfo, format, outputName)
                withContext(Dispatchers.Main) { onParsed?.invoke(m3u8Info.segments.size) }
                notifyProgress(task)

                // Phase 2: Download segments with pause support
                val concurrency = DownloadHelper.getDownloadThreads(context)
                Log.i("CDNTest", "[M3U8] Phase 2: 开始下载分片, 并发数=$concurrency")
                val results = TsDownloader.download(m3u8Info.segments, m3u8Info.keyInfo, concurrency) { done, _ ->
                    if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.CANCELLED) return@download
                    task.downloadedBytes = done.toLong()
                    notifyProgress(task)
                }
                Log.i("CDNTest", "[M3U8] Phase 2 完成: 下载了 ${results.size} 个分片结果")

                if (task.status == DownloadStatus.CANCELLED) {
                    Log.i("CDNTest", "[M3U8] 下载已取消")
                    tasks.remove(m3u8Url)
                    m3u8Tasks.remove(m3u8Url)
                    return@launch
                }

                // Phase 3: Merge/transcode
                task.fileName = "$outputName (合并中...)"
                notifyProgress(task)
                Log.i("CDNTest", "[M3U8] Phase 3: 开始合并, 格式=${format.label}")

                val outputFile = TsMerger.merge(context, results, outputName, format)
                Log.i("CDNTest", "[M3U8] Phase 3 结果: outputFile=${outputFile?.absolutePath}, exists=${outputFile?.exists()}, size=${outputFile?.length()}")

                if (task.status == DownloadStatus.CANCELLED) {
                    outputFile?.delete()
                    tasks.remove(m3u8Url)
                    m3u8Tasks.remove(m3u8Url)
                    return@launch
                }

                if (outputFile != null && outputFile.exists() && outputFile.length() > 0) {
                    task.status = DownloadStatus.COMPLETED
                    task.fileName = outputFile.name
                    task.destPath = outputFile.absolutePath
                    task.downloadedBytes = outputFile.length()
                    task.totalBytes = outputFile.length()
                    task.speed = 0
                    Log.i("CDNTest", "[M3U8] 下载完成: ${outputFile.absolutePath}, ${outputFile.length()} bytes")

                    withContext(Dispatchers.IO) {
                        DownloadRecordStore.add(context, DownloadRecord(
                            name = outputFile.name,
                            url = m3u8Url,
                            path = outputFile.absolutePath,
                            size = outputFile.length(),
                            date = task.date
                        ))
                    }

                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onComplete(task) }
                    }
                } else {
                    Log.e("CDNTest", "[M3U8] FFmpeg 合并失败: outputFile=${outputFile?.absolutePath}")
                    task.status = DownloadStatus.FAILED
                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onFailed(task, "FFmpeg 合并失败") }
                    }
                }
            } catch (e: Exception) {
                Log.e("CDNTest", "[M3U8] 异常: ${e.message}", e)
                if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.CANCELLED) return@launch
                task.status = DownloadStatus.FAILED
                withContext(Dispatchers.Main) {
                    listeners.forEach { it.onFailed(task, e.message ?: "未知错误") }
                }
            } finally {
                if (task.status != DownloadStatus.PAUSED) {
                    m3u8Tasks.remove(m3u8Url)
                }
                activeJobs.remove(m3u8Url)
            }
        }
        activeJobs[m3u8Url] = job
    }

    fun pause(url: String) {
        val task = tasks[url] ?: return
        if (task.status == DownloadStatus.RUNNING) {
            pausedUrls.add(url)
            task.status = DownloadStatus.PAUSED
            activeJobs[url]?.cancel()
            activeJobs.remove(url)
            if (!isM3u8Task(url)) {
                runningCount.decrementAndGet()
                processQueue(task.destPath.substringBeforeLast("/"))
            }
            notifyProgress(task)
        }
    }

    fun resume(context: Context, url: String) {
        val task = tasks[url] ?: return
        if (task.status == DownloadStatus.PAUSED) {
            pausedUrls.remove(url)
            task.status = DownloadStatus.PENDING
            if (isM3u8Task(url)) {
                // Re-enqueue M3U8 from scratch
                val m3u8Data = m3u8Tasks[url]
                m3u8Tasks.remove(url)
                tasks.remove(url)
                if (m3u8Data != null) {
                    enqueueM3u8(context, m3u8Data.m3u8Url, m3u8Data.outputName, m3u8Data.outputFormat)
                }
            } else {
                startDownload(context, task)
            }
        }
    }

    fun cancel(url: String) {
        val task = tasks[url] ?: return
        pausedUrls.remove(url)
        val wasRunning = task.status == DownloadStatus.RUNNING
        task.status = DownloadStatus.CANCELLED
        activeJobs[url]?.cancel()
        activeJobs.remove(url)
        if (wasRunning) {
            runningCount.decrementAndGet()
        }
        // Delete partial file
        val file = File(task.destPath)
        if (file.exists()) file.delete()
        tasks.remove(url)
        notifyCancelled(task)
    }

    fun pauseAll() {
        tasks.values.filter { it.status == DownloadStatus.RUNNING }.forEach { pause(it.url) }
    }

    fun resumeAll(context: Context) {
        tasks.values.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(context, it.url) }
    }

    fun getActiveTasks(): List<DownloadTask> {
        return tasks.values.filter {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING || it.status == DownloadStatus.PAUSED
        }.sortedByDescending { it.date }
    }

    fun getTask(url: String): DownloadTask? = tasks[url]

    fun getAllTasks(): List<DownloadTask> = tasks.values.toList().sortedByDescending { it.date }

    fun isDownloading(url: String): Boolean {
        val task = tasks[url] ?: return false
        return task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.PENDING
    }

    private fun getMaxConcurrency(): Int {
        val ctx = appContext ?: return 3
        return DownloadHelper.getMaxConcurrentDownloads(ctx)
    }

    private fun startDownload(context: Context, task: DownloadTask) {
        if (runningCount.get() >= getMaxConcurrency()) {
            synchronized(pendingQueue) {
                pendingQueue.add(task.url)
            }
            task.status = DownloadStatus.PENDING
            return
        }

        runningCount.incrementAndGet()
        task.status = DownloadStatus.RUNNING

        // Start foreground service
        try {
            val intent = Intent(context, DownloadService::class.java)
            context.startForegroundService(intent)
        } catch (_: Exception) {}

        val job = scope.launch {
            try {
                downloadFile(context, task)
            } catch (e: Exception) {
                if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.CANCELLED) {
                    return@launch
                }
                task.status = DownloadStatus.FAILED
                withContext(Dispatchers.Main) {
                    listeners.forEach { it.onFailed(task, e.message ?: "未知错误") }
                }
            } finally {
                if (task.status == DownloadStatus.RUNNING) {
                    runningCount.decrementAndGet()
                }
                activeJobs.remove(task.url)
                processQueue(task.destPath.substringBeforeLast("/"))
            }
        }
        activeJobs[task.url] = job
    }

    private suspend fun downloadFile(context: Context, task: DownloadTask) {
        val destFile = File(task.destPath)
        destFile.parentFile?.mkdirs()

        val existingBytes = if (destFile.exists()) destFile.length() else 0L

        val conn = URL(task.url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", UserAgentHelper.getCurrentUa(context))

        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=$existingBytes-")
        }

        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode !in 200..299 && responseCode != 206) {
            throw Exception("HTTP $responseCode")
        }

        val supportsRange = responseCode == 206
        val contentLength = conn.contentLength.toLong()

        if (supportsRange) {
            task.totalBytes = existingBytes + contentLength
            task.downloadedBytes = existingBytes
        } else {
            task.totalBytes = contentLength.toLong()
            task.downloadedBytes = 0
        }

        val outputStream = if (supportsRange && existingBytes > 0) {
            RandomAccessFile(destFile, "rw").apply { seek(existingBytes) }
        } else {
            null
        }

        val buffer = ByteArray(BUFFER_SIZE)
        var lastProgressTime = System.currentTimeMillis()
        var lastBytes = task.downloadedBytes

        conn.inputStream.use { input ->
            val output = outputStream ?: FileOutputStream(destFile, supportsRange && existingBytes > 0)
            output.use { out ->
                while (true) {
                    if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.CANCELLED) {
                        break
                    }

                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break

                    if (outputStream != null) {
                        outputStream.write(buffer, 0, bytesRead)
                    } else {
                        (out as FileOutputStream).write(buffer, 0, bytesRead)
                    }

                    task.downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 500) {
                        val elapsed = (now - lastProgressTime) / 1000.0
                        task.speed = ((task.downloadedBytes - lastBytes) / elapsed).toLong()
                        lastBytes = task.downloadedBytes
                        lastProgressTime = now

                        withContext(Dispatchers.Main) {
                            listeners.forEach { it.onProgress(task) }
                        }
                    }
                }
            }
        }

        outputStream?.close()
        conn.disconnect()

        if (task.status == DownloadStatus.CANCELLED) {
            if (destFile.exists()) destFile.delete()
            return
        }

        if (task.status == DownloadStatus.PAUSED) {
            return
        }

        // Verify file size
        if (task.totalBytes > 0 && destFile.length() < task.totalBytes) {
            throw Exception("下载不完整")
        }

        task.status = DownloadStatus.COMPLETED
        task.downloadedBytes = destFile.length()
        task.speed = 0

        // Update database
        withContext(Dispatchers.IO) {
            DownloadRecordStore.add(context, DownloadRecord(
                name = task.fileName,
                url = task.url,
                path = task.destPath,
                size = destFile.length(),
                date = task.date
            ))
        }

        withContext(Dispatchers.Main) {
            listeners.forEach { it.onComplete(task) }
        }
    }

    private fun processQueue(@Suppress("UNUSED_PARAMETER") downloadDir: String) {
        val ctx = appContext ?: return
        synchronized(pendingQueue) {
            while (pendingQueue.isNotEmpty() && runningCount.get() < getMaxConcurrency()) {
                val nextUrl = pendingQueue.removeAt(0)
                val task = tasks[nextUrl]
                if (task != null && task.status == DownloadStatus.PENDING) {
                    startDownload(ctx, task)
                }
            }
        }
    }

    private fun notifyProgress(task: DownloadTask) {
        scope.launch(Dispatchers.Main) {
            listeners.forEach { it.onProgress(task) }
        }
    }

    private fun notifyCancelled(task: DownloadTask) {
        scope.launch(Dispatchers.Main) {
            listeners.forEach { it.onCancelled(task) }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
