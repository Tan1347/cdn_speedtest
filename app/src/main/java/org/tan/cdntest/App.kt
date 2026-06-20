package org.tan.cdntest

import android.app.Application
import java.util.concurrent.Executors

class App : Application() {

    private val bgExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()

        // 后台自动执行 IP 优选（仅当本地无有效缓存时）
        bgExecutor.execute { autoOptimizeHosts() }
    }

    /**
     * 自动 IP 优选：
     * 1. 若本地 JSON 文件缓存有效（24h 内），跳过
     * 2. 否则获取远程 hosts + 本地测速，保存结果到文件
     */
    private fun autoOptimizeHosts() {
        if (GitHubHostsHelper.isCacheValid(this)) {
            AppLogger.d(this, "App", "IP 优选缓存有效，跳过后台任务")
            // 仍然确保 AppHttpClient 已加载优选 DNS
            AppHttpClient.get(this)
            return
        }

        AppLogger.i(this, "App", "开始后台 IP 优选...")

        try {
            // 1. 获取远程 hosts（作为备选数据源）
            val remoteHosts = GitHubHostsHelper.fetchRemoteHosts(this)
            AppLogger.i(this, "App", "远程 hosts 获取: ${remoteHosts.size} 条")

            // 2. 本地 IP 测速
            val results = GitHubHostsHelper.testAll { domain, ip, latency ->
                val latencyStr = if (latency == Long.MAX_VALUE) "超时" else "${latency}ms"
                AppLogger.d(this, "App", "测速 $domain → $ip: $latencyStr")
            }

            // 3. 测试镜像延迟并排序
            val sortedMirrors = GitHubHostsHelper.testAndSortMirrors()
            GitHubHostsHelper.saveMirrorResults(this, sortedMirrors)

            // 4. 保存结果（SharedPreferences + JSON 文件）
            GitHubHostsHelper.saveResults(this, results)

            // 5. 重建 HTTP 客户端，使优选 IP 立即生效
            AppHttpClient.invalidate()
            AppHttpClient.get(this)

            AppLogger.i(this, "App", "后台 IP 优选完成，${results.size} 个域名")
        } catch (e: Exception) {
            AppLogger.w(this, "App", "后台 IP 优选失败: ${e.message}")
        }
    }
}
