package org.tan.cdntest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class VideoInfo(
    val duration: Long,      // milliseconds
    val width: Int,
    val height: Int,
    val estimatedSize: Long, // bytes
    val thumbnail: Bitmap?
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
        val retriever = MediaMetadataRetriever()
        try {
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

            return VideoInfo(duration, width, height, estimatedSize, thumbnail)
        } finally {
            retriever.release()
        }
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
        val thumbnail = try {
            val firstSeg = m3u8Info.segments.firstOrNull() ?: return VideoInfo(durationMs, 0, 0, estimatedSize, null)
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
            return VideoInfo(durationMs, width, height, estimatedSize, thumb)
        } catch (_: Exception) { null }

        return VideoInfo(durationMs, 0, 0, estimatedSize, thumbnail)
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
