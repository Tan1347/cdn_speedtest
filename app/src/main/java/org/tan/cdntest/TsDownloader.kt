package org.tan.cdntest

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TsDownloadResult(
    val data: ByteArray,
    val index: Int
)

object TsDownloader {

    private const val DEFAULT_CONCURRENCY = 3

    suspend fun download(
        segments: List<M3u8Segment>,
        keyInfo: KeyInfo?,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): List<TsDownloadResult> = coroutineScope {
        val total = segments.size
        Log.i("CDNTest", "[TsDownloader] 开始下载 $total 个分片, 并发=$concurrency, 加密=${keyInfo?.method ?: "无"}")
        var downloaded = 0
        val semaphore = Semaphore(concurrency)
        val keyBytes = if (keyInfo != null && keyInfo.method != "NONE") {
            Log.i("CDNTest", "[TsDownloader] 获取密钥: ${keyInfo.uri}")
            fetchKey(keyInfo.uri)
        } else null

        val results = segments.map { segment ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val data = downloadSegment(segment.url)
                    val decrypted = if (keyBytes != null && keyInfo != null) {
                        decryptAes128Cbc(data, keyBytes, keyInfo.iv, segment.index)
                    } else {
                        data
                    }
                    synchronized(this@TsDownloader) {
                        downloaded++
                        if (downloaded % 10 == 0 || downloaded == total) {
                            Log.i("CDNTest", "[TsDownloader] 进度: $downloaded/$total")
                        }
                        onProgress?.invoke(downloaded, total)
                    }
                    TsDownloadResult(decrypted, segment.index)
                }
            }
        }

        results.awaitAll().sortedBy { it.index }
    }

    private fun downloadSegment(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw Exception("HTTP ${conn.responseCode}: $url")
        }
        val data = conn.inputStream.readBytes()
        conn.disconnect()
        return data
    }

    private fun fetchKey(keyUrl: String): ByteArray {
        val conn = URL(keyUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw Exception("获取密钥失败: HTTP ${conn.responseCode}")
        }
        val key = conn.inputStream.readBytes()
        conn.disconnect()
        return key
    }

    private fun decryptAes128Cbc(
        data: ByteArray,
        key: ByteArray,
        ivHex: String?,
        segmentIndex: Int
    ): ByteArray {
        val iv = if (ivHex != null) {
            hexToBytes(ivHex)
        } else {
            // Default IV: segment index as big-endian 16 bytes
            ByteArray(16).also {
                it[12] = (segmentIndex shr 24 and 0xFF).toByte()
                it[13] = (segmentIndex shr 16 and 0xFF).toByte()
                it[14] = (segmentIndex shr 8 and 0xFF).toByte()
                it[15] = (segmentIndex and 0xFF).toByte()
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
