package org.tan.cdntest

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object GitHubHostsHelper {

    // 远程 hosts 文件地址（社区维护，定时更新 GitHub 优选 IP）
    private const val REMOTE_HOSTS_URL = "https://raw.hellogithub.com/hosts"

    // 代理镜像域名列表（与 UpdateChecker 中的镜像对应）
    private val MIRROR_DOMAINS = listOf(
        "ghfast.top",
        "ghproxy.net",
        "github.moeyy.xyz"
    )

    data class IpResult(
        val ip: String,
        val latency: Long  // ms, Long.MAX_VALUE = unreachable
    )

    data class DomainResult(
        val domain: String,
        val bestIp: String,
        val bestLatency: Long,
        val allResults: List<IpResult>
    )

    private const val PREF_NAME = "github_hosts"
    private const val KEY_CACHE = "hosts_cache"
    private const val KEY_TIMESTAMP = "hosts_timestamp"
    private const val KEY_REMOTE_CACHE = "remote_hosts_cache"
    private const val KEY_REMOTE_TIMESTAMP = "remote_hosts_timestamp"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L  // 24 hours

    // GitHub 相关域名列表，用于从远程 hosts 文件中筛选
    private val GITHUB_DOMAINS = listOf(
        "github.com",
        "api.github.com",
        "github.global.ssl.fastly.net",
        "objects.githubusercontent.com",
        "raw.githubusercontent.com",
        "gist.github.com",
        "gist.githubusercontent.com",
        "codeload.github.com",
        "collector.github.com"
    )

    // GitHub 域名对应的已知 CDN IP 池
    val GITHUB_IPS = mapOf(
        "github.com" to listOf(
            "20.205.243.166",
            "140.82.121.3", "140.82.121.4",
            "140.82.114.3", "140.82.114.4",
            "20.200.245.247", "20.201.211.129",
            "20.207.73.82", "20.175.191.89"
        ),
        "github.global.ssl.fastly.net" to listOf(
            "151.101.1.69", "151.101.65.69",
            "151.101.129.69", "151.101.193.69"
        ),
        "objects.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133",
            "185.199.110.133", "185.199.111.133"
        ),
        "raw.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133",
            "185.199.110.133", "185.199.111.133"
        ),
        "gist.github.com" to listOf(
            "140.82.114.3", "140.82.114.4",
            "140.82.121.3", "140.82.121.4"
        )
    )

    /**
     * 测试单个 IP 的 TCP 连接延迟
     */
    fun testIp(ip: String, port: Int = 443, timeout: Int = 3000): Long {
        val start = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            val latency = System.currentTimeMillis() - start
            socket.close()
            latency
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * 测试所有域名的所有 IP，返回每个域名的最佳 IP
     */
    fun testAll(onProgress: (domain: String, ip: String, latency: Long) -> Unit = { _, _, _ -> }): List<DomainResult> {
        val results = mutableListOf<DomainResult>()

        for ((domain, ips) in GITHUB_IPS) {
            val ipResults = mutableListOf<IpResult>()
            var bestIp = ips[0]
            var bestLatency = Long.MAX_VALUE

            for (ip in ips) {
                // 每个 IP 测试 2 次，取平均值
                val lat1 = testIp(ip)
                val lat2 = if (lat1 < Long.MAX_VALUE) testIp(ip) else Long.MAX_VALUE
                val avgLatency = if (lat1 < Long.MAX_VALUE && lat2 < Long.MAX_VALUE) {
                    (lat1 + lat2) / 2
                } else {
                    minOf(lat1, lat2)
                }

                ipResults.add(IpResult(ip, avgLatency))
                onProgress(domain, ip, avgLatency)

                if (avgLatency < bestLatency) {
                    bestLatency = avgLatency
                    bestIp = ip
                }
            }

            results.add(DomainResult(domain, bestIp, bestLatency, ipResults.sortedBy { it.latency }))
        }

        return results
    }

    // --- JSON 文件持久化 ---

    private const val CONFIG_DIR = "config"
    private const val HOSTS_FILE = "github_hosts.json"

    /**
     * 获取 hosts 配置文件路径
     */
    private fun getHostsFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), CONFIG_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, HOSTS_FILE)
    }

    /**
     * 保存测试结果到 SharedPreferences + JSON 文件
     */
    fun saveResults(context: Context, results: List<DomainResult>) {
        val timestamp = System.currentTimeMillis()

        // 1. 保存到 SharedPreferences（内存缓存）
        val cache = JSONObject()
        for (result in results) {
            cache.put(result.domain, result.bestIp)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHE, cache.toString())
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()

        // 2. 保存到 JSON 文件（持久化）
        saveResultsToFile(context, results, timestamp)
    }

    /**
     * 将测试结果写入 JSON 文件
     * 格式:
     * {
     *   "timestamp": 1234567890,
     *   "domains": {
     *     "github.com": {
     *       "bestIp": "20.205.243.166",
     *       "bestLatency": 150,
     *       "allResults": [
     *         {"ip": "20.205.243.166", "latency": 150},
     *         {"ip": "140.82.121.3", "latency": 200}
     *       ]
     *     }
     *   }
     * }
     */
    private fun saveResultsToFile(context: Context, results: List<DomainResult>, timestamp: Long) {
        try {
            val root = JSONObject()
            root.put("timestamp", timestamp)

            val domains = JSONObject()
            for (result in results) {
                val domainObj = JSONObject()
                domainObj.put("bestIp", result.bestIp)
                domainObj.put("bestLatency", result.bestLatency)

                val allArray = JSONArray()
                for (ipResult in result.allResults) {
                    val ipObj = JSONObject()
                    ipObj.put("ip", ipResult.ip)
                    ipObj.put("latency", ipResult.latency)
                    allArray.put(ipObj)
                }
                domainObj.put("allResults", allArray)
                domains.put(result.domain, domainObj)
            }
            root.put("domains", domains)

            val file = getHostsFile(context)
            file.writeText(root.toString(2))
            AppLogger.i(this, "GitHubHosts", "配置已保存到文件: ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(this, "GitHubHosts", "保存配置文件失败: ${e.message}")
        }
    }

    /**
     * 从 JSON 文件加载测试结果
     * @return DomainResult 列表，文件不存在或无效时返回 null
     */
    fun loadResultsFromFile(context: Context): List<DomainResult>? {
        val file = getHostsFile(context)
        if (!file.exists()) return null

        return try {
            val root = JSONObject(file.readText())
            val timestamp = root.optLong("timestamp", 0)

            // 检查文件是否过期
            if (System.currentTimeMillis() - timestamp > CACHE_DURATION_MS) {
                AppLogger.d(this, "GitHubHosts", "配置文件已过期")
                return null
            }

            val domains = root.optJSONObject("domains") ?: return null
            val results = mutableListOf<DomainResult>()

            for (domain in domains.keys()) {
                val domainObj = domains.getJSONObject(domain)
                val bestIp = domainObj.getString("bestIp")
                val bestLatency = domainObj.optLong("bestLatency", Long.MAX_VALUE)

                val allResults = mutableListOf<IpResult>()
                val allArray = domainObj.optJSONArray("allResults")
                if (allArray != null) {
                    for (i in 0 until allArray.length()) {
                        val ipObj = allArray.getJSONObject(i)
                        allResults.add(IpResult(
                            ip = ipObj.getString("ip"),
                            latency = ipObj.optLong("latency", Long.MAX_VALUE)
                        ))
                    }
                }

                results.add(DomainResult(domain, bestIp, bestLatency, allResults))
            }

            AppLogger.i(this, "GitHubHosts", "从文件加载配置: ${results.size} 个域名")
            results
        } catch (e: Exception) {
            AppLogger.w(this, "GitHubHosts", "加载配置文件失败: ${e.message}")
            null
        }
    }

    /**
     * 获取缓存的最佳 IP，优先级：SharedPreferences → JSON 文件 → 远程 hosts 缓存
     */
    fun getBestIp(context: Context, domain: String): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 1. SharedPreferences 本地测速缓存
        val localTimestamp = prefs.getLong(KEY_TIMESTAMP, 0)
        if (now - localTimestamp <= CACHE_DURATION_MS) {
            val localJson = prefs.getString(KEY_CACHE, null)
            if (localJson != null) {
                try {
                    val obj = JSONObject(localJson)
                    val ip = obj.optString(domain, "").ifEmpty { null }
                    if (ip != null) return ip
                } catch (_: Exception) {}
            }
        }

        // 2. JSON 文件缓存（SharedPreferences 可能被系统清理，文件更持久）
        val fileResult = loadResultsFromFile(context)
        if (fileResult != null) {
            val found = fileResult.find { it.domain == domain }
            if (found != null) return found.bestIp
        }

        // 3. 远程 hosts 缓存
        val remoteTimestamp = prefs.getLong(KEY_REMOTE_TIMESTAMP, 0)
        if (now - remoteTimestamp <= CACHE_DURATION_MS) {
            val remoteJson = prefs.getString(KEY_REMOTE_CACHE, null)
            if (remoteJson != null) {
                try {
                    val obj = JSONObject(remoteJson)
                    val ip = obj.optString(domain, "").ifEmpty { null }
                    if (ip != null) return ip
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * 获取上次测试时间戳（SharedPreferences 优先，回退到 JSON 文件）
     */
    fun getLastTestTime(context: Context): Long {
        val prefsTime = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_TIMESTAMP, 0)
        if (prefsTime > 0) return prefsTime

        // 回退到 JSON 文件中的 timestamp
        val file = getHostsFile(context)
        if (!file.exists()) return 0
        return try {
            JSONObject(file.readText()).optLong("timestamp", 0)
        } catch (_: Exception) { 0 }
    }

    /**
     * 缓存是否有效（SharedPreferences 或 JSON 文件任一有效即可）
     */
    fun isCacheValid(context: Context): Boolean {
        return System.currentTimeMillis() - getLastTestTime(context) <= CACHE_DURATION_MS
    }

    /**
     * 清除缓存（SharedPreferences + JSON 文件）
     */
    fun clearCache(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        val file = getHostsFile(context)
        if (file.exists()) file.delete()
    }

    // --- 远程 Hosts 获取 ---

    /**
     * 从远程获取 GitHub hosts 文件，解析并缓存
     * @return 解析到的 domain→ip 映射，失败返回空 Map
     */
    fun fetchRemoteHosts(context: Context): Map<String, String> {
        return try {
            val client = AppHttpClient.get(context)
            val request = Request.Builder()
                .url(REMOTE_HOSTS_URL)
                .header("User-Agent", "CDNViewer-Android")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val text = response.body?.string() ?: ""
                response.close()
                val parsed = parseHostsFile(text)
                if (parsed.isNotEmpty()) {
                    saveRemoteHosts(context, parsed)
                }
                parsed
            } else {
                response.close()
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 解析标准 hosts 文件格式，只保留 GitHub 相关域名
     * 格式: "IP domain"，# 开头为注释
     */
    private fun parseHostsFile(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) continue

            val ip = parts[0]
            for (i in 1 until parts.size) {
                val domain = parts[i].lowercase()
                if (GITHUB_DOMAINS.any { domain == it || domain.endsWith(".$it") }) {
                    // IP 有效性简单校验
                    if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        result[domain] = ip
                    }
                }
            }
        }
        return result
    }

    /**
     * 保存远程 hosts 数据到 SharedPreferences
     */
    private fun saveRemoteHosts(context: Context, hosts: Map<String, String>) {
        val json = JSONObject()
        for ((domain, ip) in hosts) {
            json.put(domain, ip)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_CACHE, json.toString())
            .putLong(KEY_REMOTE_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * 获取远程 hosts 缓存
     */
    fun getRemoteHostsCache(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_REMOTE_CACHE, null) ?: return emptyMap()
        val timestamp = prefs.getLong(KEY_REMOTE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - timestamp > CACHE_DURATION_MS) return emptyMap()

        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                result[key] = obj.getString(key)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 获取上次远程 hosts 更新时间
     */
    fun getRemoteHostsTime(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_REMOTE_TIMESTAMP, 0)
    }

    // --- 镜像延迟测试 ---

    private const val KEY_MIRROR_CACHE = "mirror_latency_cache"
    private const val KEY_MIRROR_TIMESTAMP = "mirror_latency_timestamp"

    /**
     * 测试镜像域名的延迟（通过 DNS 解析获取 IP 后测试 TCP 连接）
     */
    fun testMirrorLatency(domain: String, timeout: Int = 3000): Long {
        return try {
            val addresses = java.net.InetAddress.getAllByName(domain)
            if (addresses.isEmpty()) return Long.MAX_VALUE
            testIp(addresses[0].hostAddress ?: return Long.MAX_VALUE, 443, timeout)
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * 测试所有镜像延迟并排序，返回排序后的镜像 URL 前缀列表
     */
    fun testAndSortMirrors(): List<String> {
        data class MirrorLatency(val prefix: String, val latency: Long)

        val mirrorLatencies = mutableListOf<MirrorLatency>()
        // 直连排在最前（延迟为 0，表示不使用代理）
        mirrorLatencies.add(MirrorLatency("", 0))

        for (domain in MIRROR_DOMAINS) {
            val latency = testMirrorLatency(domain)
            mirrorLatencies.add(MirrorLatency("https://$domain/", latency))
        }

        // 按延迟排序，直连保持在第一位
        val sorted = mutableListOf(mirrorLatencies[0])
        sorted.addAll(mirrorLatencies.drop(1).sortedBy { it.latency })

        return sorted.map { it.prefix }
    }

    /**
     * 获取排序后的 API 镜像列表（带缓存）
     * 格式: ["", "https://ghfast.top/", ...]（直连 + 代理，按延迟排序）
     */
    fun getSortedApiMirrors(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_MIRROR_TIMESTAMP, 0)
        val cached = prefs.getString(KEY_MIRROR_CACHE, null)

        if (cached != null && System.currentTimeMillis() - timestamp <= CACHE_DURATION_MS) {
            return try {
                val arr = org.json.JSONArray(cached)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                getDefaultApiMirrors()
            }
        }
        return getDefaultApiMirrors()
    }

    /**
     * 获取排序后的下载镜像列表（带缓存）
     */
    fun getSortedDownloadMirrors(context: Context): List<String> {
        return getSortedApiMirrors(context).filter { it.isNotEmpty() }
    }

    /**
     * 保存镜像延迟排序结果
     */
    fun saveMirrorResults(context: Context, sortedMirrors: List<String>) {
        val arr = org.json.JSONArray(sortedMirrors)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MIRROR_CACHE, arr.toString())
            .putLong(KEY_MIRROR_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun getDefaultApiMirrors(): List<String> = listOf(
        "",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/"
    )
}
