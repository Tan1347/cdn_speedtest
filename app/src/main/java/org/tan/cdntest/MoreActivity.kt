package org.tan.cdntest

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tan.cdntest.ui.theme.AppColors
import org.tan.cdntest.ui.theme.CDNTestTheme

class MoreActivity : ComponentActivity() {

    private var logEnabled by mutableStateOf(true)
    private var logLevelText by mutableStateOf("")
    private var logDirText by mutableStateOf("")
    private var hostsStatusText by mutableStateOf("")
    private var hostsStatusIsAccent by mutableStateOf(false)
    private var currentVersion by mutableStateOf("")
    private var latestVersion by mutableStateOf("")
    private var showLatestVersion by mutableStateOf(false)
    private var updateStatusText by mutableStateOf("")
    private var updateStatusIsAccent by mutableStateOf(false)
    private var showUpdateStatus by mutableStateOf(false)
    private var releaseNotes by mutableStateOf("")
    private var showReleaseNotes by mutableStateOf(false)
    private var isCheckingUpdate by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val updater = UpdateChecker(this)
        currentVersion = updater.getCurrentVersion()
        logEnabled = AppLogger.isEnabled(this)
        refreshLogLevelDisplay()
        val logDir = java.io.File(getExternalFilesDir(null), "log")
        logDirText = "日志目录: ${logDir.absolutePath}"

        setContent {
            CDNTestTheme {
                MoreScreen(
                    onBack = { finish() },
                    logEnabled = logEnabled,
                    logLevelText = logLevelText,
                    logDirText = logDirText,
                    hostsStatusText = hostsStatusText,
                    hostsStatusIsAccent = hostsStatusIsAccent,
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    showLatestVersion = showLatestVersion,
                    updateStatusText = updateStatusText,
                    updateStatusIsAccent = updateStatusIsAccent,
                    showUpdateStatus = showUpdateStatus,
                    releaseNotes = releaseNotes,
                    showReleaseNotes = showReleaseNotes,
                    isCheckingUpdate = isCheckingUpdate,
                    onBrowserClick = {
                        startActivity(Intent(this, BrowserSettingsActivity::class.java))
                    },
                    onLogToggle = { checked ->
                        logEnabled = checked
                        AppLogger.setEnabled(this, checked)
                    },
                    onLogLevelClick = { showLogLevelDialog() },
                    onHostsTestClick = {
                        startActivity(Intent(this, HostsTestActivity::class.java))
                    },
                    onCheckUpdate = {
                        isCheckingUpdate = true
                        showUpdateStatus = true
                        updateStatusText = "正在检查更新..."
                        updateStatusIsAccent = false
                        showReleaseNotes = false

                        updater.checkForUpdate { release ->
                            mainHandler.post {
                                isCheckingUpdate = false
                                if (release != null) {
                                    showLatestVersion = true
                                    latestVersion = release.tagName
                                    updateStatusText = "发现新版本"
                                    updateStatusIsAccent = true

                                    if (release.body.isNotBlank()) {
                                        showReleaseNotes = true
                                        releaseNotes = release.body
                                    }

                                    updater.showUpdateDialog(release)
                                } else {
                                    showLatestVersion = false
                                    updateStatusText = "已是最新版本"
                                    updateStatusIsAccent = false
                                }
                            }
                        }
                    },
                    onDownloadManagerClick = {
                        startActivity(Intent(this, DownloadManagerActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHostsStatus()
    }

    private fun refreshLogLevelDisplay() {
        val level = AppLogger.getLevel(this)
        logLevelText = "当前: ${when (level) {
            AppLogger.Level.DEBUG -> "Debug (全部日志)"
            AppLogger.Level.INFO -> "Info (默认)"
            AppLogger.Level.WARN -> "Warning (警告及以上)"
            AppLogger.Level.ERROR -> "Error (仅错误)"
        }}"
    }

    private fun showLogLevelDialog() {
        val items = arrayOf(
            "Debug (全部日志)",
            "Info (默认)",
            "Warning (警告及以上)",
            "Error (仅错误)"
        )
        val currentLevel = AppLogger.getLevel(this)
        val checkedIndex = when (currentLevel) {
            AppLogger.Level.DEBUG -> 0
            AppLogger.Level.INFO -> 1
            AppLogger.Level.WARN -> 2
            AppLogger.Level.ERROR -> 3
        }

        AlertDialog.Builder(this)
            .setTitle("日志等级")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val newLevel = when (which) {
                    0 -> AppLogger.Level.DEBUG
                    1 -> AppLogger.Level.INFO
                    2 -> AppLogger.Level.WARN
                    3 -> AppLogger.Level.ERROR
                    else -> AppLogger.Level.INFO
                }
                AppLogger.setLevel(this, newLevel)
                refreshLogLevelDisplay()
                dialog.dismiss()
                Toast.makeText(this, "日志等级: ${newLevel.tag}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshHostsStatus() {
        val lastTime = GitHubHostsHelper.getLastTestTime(this)
        if (lastTime > 0 && GitHubHostsHelper.isCacheValid(this)) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastTime))
            hostsStatusText = "上次测试: $date (有效)"
            hostsStatusIsAccent = true
        } else if (lastTime > 0) {
            hostsStatusText = "上次测试结果已过期，建议重新测试"
            hostsStatusIsAccent = false
        } else {
            hostsStatusText = "尚未测试"
            hostsStatusIsAccent = false
        }
    }
}

@Composable
private fun MoreScreen(
    onBack: () -> Unit,
    logEnabled: Boolean,
    logLevelText: String,
    logDirText: String,
    hostsStatusText: String,
    hostsStatusIsAccent: Boolean,
    currentVersion: String,
    latestVersion: String,
    showLatestVersion: Boolean,
    updateStatusText: String,
    updateStatusIsAccent: Boolean,
    showUpdateStatus: Boolean,
    releaseNotes: String,
    showReleaseNotes: Boolean,
    isCheckingUpdate: Boolean,
    onBrowserClick: () -> Unit,
    onLogToggle: (Boolean) -> Unit,
    onLogLevelClick: () -> Unit,
    onHostsTestClick: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadManagerClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", color = AppColors.white) },
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
            // Browser settings card
            SettingCard(
                title = "浏览器设置",
                summary = "浏览器标识、搜索引擎",
                onClick = onBrowserClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Log settings card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "日志设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary()
                    )

                    // Log switch row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "启用日志",
                            fontSize = 14.sp,
                            color = AppColors.textPrimary(),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = logEnabled,
                            onCheckedChange = onLogToggle
                        )
                    }

                    // Log level (visible when log enabled)
                    if (logEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onLogLevelClick)
                                .padding(top = 8.dp)
                        ) {
                            Text(
                                text = "日志等级",
                                fontSize = 14.sp,
                                color = AppColors.textPrimary()
                            )
                            Text(
                                text = logLevelText,
                                fontSize = 13.sp,
                                color = AppColors.textSecondary(),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    // Log dir
                    Text(
                        text = logDirText,
                        fontSize = 12.sp,
                        color = AppColors.textSecondary(),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // GitHub Hosts card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GitHub Hosts 优选",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary()
                    )
                    Text(
                        text = "测试 GitHub 域名的最优 IP 节点，提升更新检查和下载速度",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = hostsStatusText,
                        fontSize = 13.sp,
                        color = if (hostsStatusIsAccent) MaterialTheme.colors.secondary else AppColors.textSecondary(),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    OutlinedButton(
                        onClick = onHostsTestClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Hosts 优选测试")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Version info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "版本信息",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary()
                    )

                    // Current version
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = "当前版本",
                            fontSize = 14.sp,
                            color = AppColors.textSecondary(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = currentVersion,
                            fontSize = 14.sp,
                            color = AppColors.textPrimary()
                        )
                    }

                    // Latest version
                    if (showLatestVersion) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(
                                text = "最新版本",
                                fontSize = 14.sp,
                                color = AppColors.textSecondary(),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = latestVersion,
                                fontSize = 14.sp,
                                color = MaterialTheme.colors.secondary
                            )
                        }
                    }

                    // Update status
                    if (showUpdateStatus) {
                        Text(
                            text = updateStatusText,
                            fontSize = 13.sp,
                            color = if (updateStatusIsAccent) MaterialTheme.colors.secondary else AppColors.textSecondary(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }

                    // Check update button
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        enabled = !isCheckingUpdate,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isCheckingUpdate) "检查中..." else "检查更新")
                    }

                    // Release notes card
                    if (showReleaseNotes) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            elevation = 0.dp,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "更新日志",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.textPrimary()
                                )
                                Text(
                                    text = releaseNotes,
                                    fontSize = 13.sp,
                                    color = AppColors.textSecondary(),
                                    modifier = Modifier.padding(top = 4.dp),
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Download manager button
            OutlinedButton(
                onClick = onDownloadManagerClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("下载管理", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, summary: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textPrimary()
                )
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = AppColors.textSecondary(),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = "›",
                fontSize = 20.sp,
                color = AppColors.textSecondary()
            )
        }
    }
}
