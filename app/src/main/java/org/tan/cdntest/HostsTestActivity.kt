package org.tan.cdntest

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tan.cdntest.ui.theme.AppColors
import org.tan.cdntest.ui.theme.CDNTestTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HostsTestActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private var testRunning by mutableStateOf(false)
    private var testResults by mutableStateOf<List<GitHubHostsHelper.DomainResult>>(emptyList())
    private var progressValue by mutableStateOf(0f)
    private var progressTitle by mutableStateOf("")
    private var progressDetail by mutableStateOf("")
    private var applyEnabled by mutableStateOf(false)
    private var showResults by mutableStateOf(false)
    private var startButtonText by mutableStateOf("开始测试")
    private var cacheStatusText by mutableStateOf("")

    // Remote hosts fetched during test
    private var remoteHosts by mutableStateOf<Map<String, String>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateCacheStatus()

        setContent {
            CDNTestTheme {
                HostsTestScreen(
                    testRunning = testRunning,
                    cacheStatusText = cacheStatusText,
                    startButtonText = startButtonText,
                    applyEnabled = applyEnabled,
                    progressValue = progressValue,
                    progressTitle = progressTitle,
                    progressDetail = progressDetail,
                    showResults = showResults,
                    testResults = testResults,
                    remoteHosts = remoteHosts,
                    onStartTest = { startTest() },
                    onApplyResults = { applyResults() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun updateCacheStatus() {
        val statusParts = mutableListOf<String>()

        val lastTime = GitHubHostsHelper.getLastTestTime(this)
        if (lastTime > 0 && GitHubHostsHelper.isCacheValid(this)) {
            statusParts.add("本地测试: ${dateFormat.format(Date(lastTime))} (有效)")
        } else if (lastTime > 0) {
            statusParts.add("本地测试: ${dateFormat.format(Date(lastTime))} (已过期)")
        } else {
            statusParts.add("本地测试: 未测试")
        }

        val remoteTime = GitHubHostsHelper.getRemoteHostsTime(this)
        if (remoteTime > 0 && System.currentTimeMillis() - remoteTime <= 24 * 60 * 60 * 1000L) {
            val remoteCache = GitHubHostsHelper.getRemoteHostsCache(this)
            statusParts.add("远程 hosts: ${dateFormat.format(Date(remoteTime))} (${remoteCache.size}条)")
        } else {
            statusParts.add("远程 hosts: 未获取")
        }

        cacheStatusText = statusParts.joinToString("\n")
    }

    private fun startTest() {
        testRunning = true
        startButtonText = "测试中..."
        applyEnabled = false
        showResults = false
        progressValue = 0f
        testResults = emptyList()
        remoteHosts = emptyMap()

        val totalIps = GitHubHostsHelper.GITHUB_IPS.values.sumOf { it.size }
        var testedCount = 0

        executor.execute {
            // 1. 获取远程 hosts
            mainHandler.post {
                progressTitle = "正在获取远程 hosts..."
                progressDetail = "从 raw.hellogithub.com 获取最新 GitHub IP"
            }
            val fetchedRemoteHosts = GitHubHostsHelper.fetchRemoteHosts(this@HostsTestActivity)
            AppLogger.i(this, "HostsTest", "远程 hosts 获取: ${fetchedRemoteHosts.size} 条记录")

            // 2. 本地 IP 测速
            val results = GitHubHostsHelper.testAll { domain, ip, latency ->
                testedCount++
                val progress = (testedCount.toFloat() / totalIps)
                val latencyStr = if (latency == Long.MAX_VALUE) "超时" else "${latency}ms"
                mainHandler.post {
                    progressValue = progress
                    progressTitle = "正在测试 ($testedCount/$totalIps)"
                    progressDetail = "$domain → $ip: $latencyStr"
                }
            }

            // 3. 测试镜像延迟并排序
            mainHandler.post {
                progressDetail = "正在测试镜像延迟..."
            }
            val sortedMirrors = GitHubHostsHelper.testAndSortMirrors()
            GitHubHostsHelper.saveMirrorResults(this@HostsTestActivity, sortedMirrors)

            mainHandler.post {
                testRunning = false
                startButtonText = "重新测试"
                applyEnabled = true
                testResults = results
                remoteHosts = fetchedRemoteHosts
                showResults = true
                updateCacheStatus()
                AppLogger.i(this, "HostsTest", "Hosts 测试完成，远程: ${fetchedRemoteHosts.size}条，镜像排序: $sortedMirrors")
            }
        }
    }

    private fun applyResults() {
        if (testResults.isEmpty()) {
            Toast.makeText(this, "请先进行测试", Toast.LENGTH_SHORT).show()
            return
        }
        GitHubHostsHelper.saveResults(this, testResults)
        updateCacheStatus()
        Toast.makeText(this, "Hosts 优选结果已应用", Toast.LENGTH_SHORT).show()
        AppLogger.i(this, "HostsTest", "Hosts 优选结果已保存")
    }
}

@Composable
fun HostsTestScreen(
    testRunning: Boolean,
    cacheStatusText: String,
    startButtonText: String,
    applyEnabled: Boolean,
    progressValue: Float,
    progressTitle: String,
    progressDetail: String,
    showResults: Boolean,
    testResults: List<GitHubHostsHelper.DomainResult>,
    remoteHosts: Map<String, String>,
    onStartTest: () -> Unit,
    onApplyResults: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub Hosts 优选", color = AppColors.white) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = AppColors.white
                        )
                    }
                },
                backgroundColor = AppColors.primary
            )
        },
        backgroundColor = AppColors.background()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Info card
            InfoCard(
                cacheStatusText = cacheStatusText,
                startButtonText = startButtonText,
                applyEnabled = applyEnabled,
                testRunning = testRunning,
                onStartTest = onStartTest,
                onApplyResults = onApplyResults
            )

            // Progress card
            if (testRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                ProgressCard(
                    progressValue = progressValue,
                    progressTitle = progressTitle,
                    progressDetail = progressDetail
                )
            }

            // Results
            if (showResults) {
                Spacer(modifier = Modifier.height(12.dp))
                ResultsSection(
                    testResults = testResults,
                    remoteHosts = remoteHosts
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    cacheStatusText: String,
    startButtonText: String,
    applyEnabled: Boolean,
    testRunning: Boolean,
    onStartTest: () -> Unit,
    onApplyResults: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "测试 GitHub 相关域名的多个 IP 节点，找出延迟最低的 IP，用于优化更新检查和下载速度。",
                fontSize = 14.sp,
                color = AppColors.textSecondary(),
                lineHeight = 20.sp
            )

            if (cacheStatusText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = cacheStatusText,
                    fontSize = 13.sp,
                    color = AppColors.textSecondary()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartTest,
                    enabled = !testRunning,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(startButtonText)
                }
                OutlinedButton(
                    onClick = onApplyResults,
                    enabled = applyEnabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("应用结果")
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(
    progressValue: Float,
    progressTitle: String,
    progressDetail: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = progressTitle,
                fontSize = 14.sp,
                color = AppColors.textPrimary()
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progressValue.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colors.secondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progressDetail,
                fontSize = 12.sp,
                color = AppColors.textSecondary()
            )
        }
    }
}

@Composable
private fun ResultsSection(
    testResults: List<GitHubHostsHelper.DomainResult>,
    remoteHosts: Map<String, String>
) {
    // Remote hosts result
    if (remoteHosts.isNotEmpty()) {
        ResultCard(
            title = "远程 Hosts (hellogithub.com)",
            entries = remoteHosts.map { "${it.key} → ${it.value}" }
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Local speed test results
    for (result in testResults) {
        DomainResultCard(result = result)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ResultCard(title: String, entries: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.textPrimary()
            )
            for (entry in entries) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry,
                    fontSize = 13.sp,
                    color = AppColors.textSecondary()
                )
            }
        }
    }
}

@Composable
private fun DomainResultCard(result: GitHubHostsHelper.DomainResult) {
    val bestLatencyStr = if (result.bestLatency == Long.MAX_VALUE) "不可达" else "${result.bestLatency}ms"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = result.domain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.textPrimary()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "最佳 IP: ${result.bestIp}  ($bestLatencyStr)",
                fontSize = 14.sp,
                color = MaterialTheme.colors.secondary
            )
            for (ipResult in result.allResults) {
                val latencyStr = if (ipResult.latency == Long.MAX_VALUE) "超时" else "${ipResult.latency}ms"
                val isBest = ipResult.ip == result.bestIp
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${ipResult.ip}  →  $latencyStr${if (isBest) "  ★" else ""}",
                    fontSize = 13.sp,
                    color = if (isBest) MaterialTheme.colors.secondary else AppColors.textSecondary()
                )
            }
        }
    }
}
