package org.tan.cdntest

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class VideoInfo(
    val duration: Long,      // milliseconds
    val width: Int,
    val height: Int,
    val estimatedSize: Long, // bytes
    val thumbnail: Bitmap?,
    val segmentCount: Int = 0
)

object VideoInfoFetcher {

    private val thumbnailCache = LruCache<String, Bitmap>(20)

    suspend fun fetch(url: String, isM3u8: Boolean): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            if (isM3u8) fetchM3u8(url) else fetchDirect(url)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchDirect(url: String): VideoInfo? {
        val cached = thumbnailCache.get(url)
        val isHttps = url.startsWith("https://", ignoreCase = true)

        // Try MediaMetadataRetriever first (handles HTTPS via Android media framework)
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(url, HashMap())
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
            val estimatedSize = if (bitrate > 0 && duration > 0) bitrate * duration / 8 / 1000 else 0

            val thumbnail = cached ?: try {
                val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, 160, 90, true)
                    if (scaled !== frame) frame.recycle()
                    thumbnailCache.put(url, scaled)
                    scaled
                } else null
            } catch (_: Exception) { null }

            retriever.release()
            if (duration > 0) return VideoInfo(duration, width, height, estimatedSize, thumbnail)
        } catch (_: Exception) {
            // MediaMetadataRetriever failed — System.err is printed by the framework, can't suppress
        }

        // Fallback: FFmpeg probe (HTTP only, this build doesn't support HTTPS)
        if (!isHttps) {
            val info = fetchWithFfprobe(url, cached)
            if (info != null) {
                val headSize = fetchFileSize(url)
                if (info.estimatedSize == 0L && headSize > 0) {
                    return VideoInfo(info.duration, info.width, info.height, headSize, info.thumbnail)
                }
                return info
            }
        }

        // Last resort: file size via HEAD
        val headSize = fetchFileSize(url)
        if (headSize > 0) return VideoInfo(0, 0, 0, headSize, null)
        return null
    }

    private fun fetchWithFfprobe(url: String, cachedThumbnail: Bitmap?): VideoInfo? {
        return try {
            val session = FFmpegKit.execute("-i \"$url\" -t 1 -f null - 2>&1")
            val output = session.output ?: return null

            val duration = parseDuration(output)
            val resolution = parseResolution(output)
            val bitrate = parseBitrate(output)
            val estimatedSize = if (bitrate > 0 && duration > 0) bitrate * duration / 8 / 1000 else 0

            // Try to grab a thumbnail frame via FFmpeg
            val thumbnail = cachedThumbnail ?: try {
                val thumbFile = java.io.File.createTempFile("thumb_", ".jpg")
                val thumbSession = FFmpegKit.execute("-i \"$url\" -ss 00:00:01 -frames:v 1 -y \"${thumbFile.absolutePath}\"")
                if (ReturnCode.isSuccess(thumbSession.returnCode) && thumbFile.exists() && thumbFile.length() > 0) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(thumbFile.absolutePath)
                    if (bmp != null) {
                        val scaled = Bitmap.createScaledBitmap(bmp, 160, 90, true)
                        if (scaled !== bmp) bmp.recycle()
                        thumbnailCache.put(url, scaled)
                        scaled
                    } else null
                } else null
            } catch (_: Exception) { null }

            VideoInfo(duration, resolution.first, resolution.second, estimatedSize, thumbnail)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDuration(output: String): Long {
        val match = Regex("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})").find(output) ?: return 0
        val h = match.groupValues[1].toLongOrNull() ?: 0
        val m = match.groupValues[2].toLongOrNull() ?: 0
        val s = match.groupValues[3].toLongOrNull() ?: 0
        val cs = match.groupValues[4].toLongOrNull() ?: 0
        return (h * 3600 + m * 60 + s) * 1000 + cs * 10
    }

    private fun parseResolution(output: String): Pair<Int, Int> {
        val match = Regex("(\\d{2,5})x(\\d{2,5})").find(output) ?: return Pair(0, 0)
        return Pair(match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2].toIntOrNull() ?: 0)
    }

    private fun parseBitrate(output: String): Long {
        val match = Regex("bitrate:\\s*(\\d+)\\s*kb/s").find(output) ?: return 0
        return (match.groupValues[1].toLongOrNull() ?: 0) * 1000
    }

    private fun fetchM3u8(url: String): VideoInfo? {
        val m3u8Info = M3u8Parser.fetchAndParse(url) ?: return null
        val durationMs = (m3u8Info.totalDuration * 1000).toLong()

        // Estimate size from first segment
        val estimatedSize = if (m3u8Info.segments.isNotEmpty()) {
            try {
                val conn = URL(m3u8Info.segments[0].url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "HEAD"
                conn.connect()
                val segSize = conn.contentLength.toLong()
                conn.disconnect()
                if (segSize > 0) segSize * m3u8Info.segments.size else 0
            } catch (_: Exception) { 0 }
        } else 0

        // Try to get thumbnail from first segment
        val firstSeg = m3u8Info.segments.firstOrNull()
        val segCount = m3u8Info.segments.size
        if (firstSeg == null) return VideoInfo(durationMs, 0, 0, estimatedSize, null, segCount)

        // Try MediaMetadataRetriever on first segment
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(firstSeg.url, HashMap())
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            val thumb = if (frame != null) {
                val scaled = Bitmap.createScaledBitmap(frame, 160, 90, true)
                if (scaled !== frame) frame.recycle()
                thumbnailCache.put(url, scaled)
                scaled
            } else null
            return VideoInfo(durationMs, width, height, estimatedSize, thumb, segCount)
        } catch (_: Exception) {
            // MediaMetadataRetriever failed — return without thumbnail/resolution
            return VideoInfo(durationMs, 0, 0, estimatedSize, null, segCount)
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun fetchFileSize(url: String): Long {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connect()
            val size = conn.contentLength.toLong()
            conn.disconnect()
            if (size > 0) size else 0
        } catch (_: Exception) { 0 }
    }
}
