package org.tan.cdntest

import android.util.Log
import okhttp3.Request
import java.net.URI

data class M3u8Segment(
    val url: String,
    val duration: Double,
    val index: Int
)

data class KeyInfo(
    val method: String,
    val uri: String,
    val iv: String?
)

data class M3u8Info(
    val segments: List<M3u8Segment>,
    val totalDuration: Double,
    val keyInfo: KeyInfo?
)

object M3u8Parser {

    fun parse(content: String, baseUrl: String): M3u8Info {
        val lines = content.lines()
        val segments = mutableListOf<M3u8Segment>()
        var totalDuration = 0.0
        var keyInfo: KeyInfo? = null
        var pendingDuration = 0.0
        var index = 0

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    keyInfo = parseKeyInfo(trimmed, baseUrl)
                }
                trimmed.startsWith("#EXTINF:") -> {
                    val value = trimmed.removePrefix("#EXTINF:")
                    pendingDuration = value.substringBefore(",").toDoubleOrNull() ?: 0.0
                }
                trimmed.startsWith("#") || trimmed.isEmpty() -> { /* skip */ }
                else -> {
                    val url = resolveUrl(trimmed, baseUrl)
                    segments.add(M3u8Segment(url, pendingDuration, index++))
                    totalDuration += pendingDuration
                    pendingDuration = 0.0
                }
            }
        }

        return M3u8Info(segments, totalDuration, keyInfo)
    }

    private fun parseKeyInfo(line: String, baseUrl: String): KeyInfo {
        val attrs = parseAttributes(line)
        val method = attrs["METHOD"] ?: "NONE"
        val keyUri = attrs["URI"]?.removeSurrounding("\"") ?: ""
        val resolvedUri = if (keyUri.isNotEmpty()) resolveUrl(keyUri, baseUrl) else ""
        val iv = attrs["IV"]
        return KeyInfo(method, resolvedUri, iv)
    }

    private fun parseAttributes(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val afterColon = line.substringAfter(":")
        val parts = afterColon.split(",")
        for (part in parts) {
            val eqIdx = part.indexOf('=')
            if (eqIdx > 0) {
                val key = part.substring(0, eqIdx).trim()
                val value = part.substring(eqIdx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (_: Exception) {
            url
        }
    }

    fun fetchAndParse(url: String): M3u8Info? {
        return try {
            Log.i("CDNTest", "[M3u8Parser] fetchAndParse: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()

            val response = HttpClient.client.newCall(request).execute()
            Log.i("CDNTest", "[M3u8Parser] HTTP ${response.code}, contentLength=${response.body?.contentLength()}")

            val content = response.body?.string() ?: throw Exception("响应体为空")
            response.close()

            Log.i("CDNTest", "[M3u8Parser] 内容长度: ${content.length} 字符, 前200: ${content.take(200)}")
            val result = parse(content, url)
            Log.i("CDNTest", "[M3u8Parser] 解析结果: ${result.segments.size} 个分片, 总时长=${result.totalDuration}s, key=${result.keyInfo?.method}")
            result
        } catch (e: Exception) {
            Log.e("CDNTest", "[M3u8Parser] fetchAndParse 异常: ${e.message}", e)
            null
        }
    }
}
