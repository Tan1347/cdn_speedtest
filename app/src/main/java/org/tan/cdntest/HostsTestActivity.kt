package org.tan.cdntest

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HostsTestActivity : AppCompatActivity() {

    private lateinit var tvCacheStatus: TextView
    private lateinit var btnStartTest: MaterialButton
    private lateinit var btnApply: MaterialButton
    private lateinit var cardProgress: MaterialCardView
    private lateinit var tvProgressTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressDetail: TextView
    private lateinit var layoutResults: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var testResults: List<GitHubHostsHelper.DomainResult> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hosts_test)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvCacheStatus = findViewById(R.id.tvCacheStatus)
        btnStartTest = findViewById(R.id.btnStartTest)
        btnApply = findViewById(R.id.btnApply)
        cardProgress = findViewById(R.id.cardProgress)
        tvProgressTitle = findViewById(R.id.tvProgressTitle)
        progressBar = findViewById(R.id.progressBar)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        layoutResults = findViewById(R.id.layoutResults)

        updateCacheStatus()

        btnStartTest.setOnClickListener { startTest() }
        btnApply.setOnClickListener { applyResults() }
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

        tvCacheStatus.text = statusParts.joinToString("\n")
        tvCacheStatus.setTextColor(getColor(R.color.text_secondary))
    }

    private fun startTest() {
        btnStartTest.isEnabled = false
        btnStartTest.text = "测试中..."
        btnApply.isEnabled = false
        cardProgress.visibility = View.VISIBLE
        layoutResults.visibility = View.GONE
        layoutResults.removeAllViews()

        val totalIps = GitHubHostsHelper.GITHUB_IPS.values.sumOf { it.size }
        var testedCount = 0

        executor.execute {
            // 1. 获取远程 hosts
            mainHandler.post {
                tvProgressTitle.text = "正在获取远程 hosts..."
                tvProgressDetail.text = "从 raw.hellogithub.com 获取最新 GitHub IP"
            }
            val remoteHosts = GitHubHostsHelper.fetchRemoteHosts(this@HostsTestActivity)
            AppLogger.i(this, "HostsTest", "远程 hosts 获取: ${remoteHosts.size} 条记录")

            // 2. 本地 IP 测速
            val results = GitHubHostsHelper.testAll { domain, ip, latency ->
                testedCount++
                val progress = (testedCount * 100 / totalIps)
                val latencyStr = if (latency == Long.MAX_VALUE) "超时" else "${latency}ms"
                mainHandler.post {
                    progressBar.progress = progress
                    tvProgressTitle.text = "正在测试 ($testedCount/$totalIps)"
                    tvProgressDetail.text = "$domain → $ip: $latencyStr"
                }
            }

            testResults = results

            // 3. 测试镜像延迟并排序
            mainHandler.post {
                tvProgressDetail.text = "正在测试镜像延迟..."
            }
            val sortedMirrors = GitHubHostsHelper.testAndSortMirrors()
            GitHubHostsHelper.saveMirrorResults(this@HostsTestActivity, sortedMirrors)

            mainHandler.post {
                btnStartTest.isEnabled = true
                btnStartTest.text = "重新测试"
                btnApply.isEnabled = true
                cardProgress.visibility = View.GONE
                displayResults(results)
                updateCacheStatus()
                AppLogger.i(this, "HostsTest", "Hosts 测试完成，远程: ${remoteHosts.size}条，镜像排序: $sortedMirrors")
            }
        }
    }

    private fun displayResults(results: List<GitHubHostsHelper.DomainResult>) {
        layoutResults.visibility = View.VISIBLE
        layoutResults.removeAllViews()

        // 远程 hosts 结果
        val remoteHosts = GitHubHostsHelper.getRemoteHostsCache(this)
        if (remoteHosts.isNotEmpty()) {
            addResultCard("远程 Hosts (hellogithub.com)", remoteHosts.map { "${it.key} → ${it.value}" })
        }

        // 本地测速结果
        for (result in results) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                radius = dp(12).toFloat()
                cardElevation = dp(2).toFloat()
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            // Domain name
            content.addView(TextView(this).apply {
                text = result.domain
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            })

            // Best IP
            val bestLatencyStr = if (result.bestLatency == Long.MAX_VALUE) "不可达" else "${result.bestLatency}ms"
            content.addView(TextView(this).apply {
                text = "最佳 IP: ${result.bestIp}  ($bestLatencyStr)"
                textSize = 14f
                setTextColor(getColor(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })

            // All IPs
            for (ipResult in result.allResults) {
                val latencyStr = if (ipResult.latency == Long.MAX_VALUE) "超时" else "${ipResult.latency}ms"
                val isBest = ipResult.ip == result.bestIp
                content.addView(TextView(this).apply {
                    text = "${ipResult.ip}  →  $latencyStr${if (isBest) "  ★" else ""}"
                    textSize = 13f
                    setTextColor(getColor(if (isBest) R.color.accent else R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }

            card.addView(content)
            layoutResults.addView(card)
        }
    }

    private fun addResultCard(title: String, entries: List<String>) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        content.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })

        for (entry in entries) {
            content.addView(TextView(this).apply {
                text = entry
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
        }

        card.addView(content)
        layoutResults.addView(card)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
