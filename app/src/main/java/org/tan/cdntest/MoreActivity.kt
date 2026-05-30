package org.tan.cdntest

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MoreActivity : AppCompatActivity() {

    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var layoutLatestVersion: View
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var cardReleaseNotes: MaterialCardView
    private lateinit var tvReleaseNotes: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        layoutLatestVersion = findViewById(R.id.layoutLatestVersion)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        cardReleaseNotes = findViewById(R.id.cardReleaseNotes)
        tvReleaseNotes = findViewById(R.id.tvReleaseNotes)

        setupBrowserSection()
        setupLogSection()
        setupHostsSection()
        setupVersionSection()
    }

    override fun onResume() {
        super.onResume()
        refreshHostsStatus(findViewById(R.id.tvHostsStatus))
    }

    // --- Browser ---
    private fun setupBrowserSection() {
        findViewById<View>(R.id.layoutBrowserSettings).setOnClickListener {
            startActivity(Intent(this, BrowserSettingsActivity::class.java))
        }
    }

    // --- Log Settings ---
    private fun setupLogSection() {
        val switchLog = findViewById<SwitchMaterial>(R.id.switchLog)
        val layoutLogLevel = findViewById<View>(R.id.layoutLogLevel)
        val tvLogLevel = findViewById<TextView>(R.id.tvLogLevel)
        val tvLogDir = findViewById<TextView>(R.id.tvLogDir)

        val enabled = AppLogger.isEnabled(this)
        switchLog.isChecked = enabled
        layoutLogLevel.visibility = if (enabled) View.VISIBLE else View.GONE
        refreshLogLevelDisplay(tvLogLevel)

        switchLog.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppLogger.setEnabled(this, isChecked)
            layoutLogLevel.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        layoutLogLevel.setOnClickListener { showLogLevelDialog(tvLogLevel) }

        val logDir = java.io.File(getExternalFilesDir(null), "log")
        tvLogDir.text = "日志目录: ${logDir.absolutePath}"
    }

    private fun refreshLogLevelDisplay(tv: TextView) {
        val level = AppLogger.getLevel(this)
        tv.text = "当前: ${when (level) {
            AppLogger.Level.DEBUG -> "Debug (全部日志)"
            AppLogger.Level.INFO -> "Info (默认)"
            AppLogger.Level.WARN -> "Warning (警告及以上)"
            AppLogger.Level.ERROR -> "Error (仅错误)"
        }}"
    }

    private fun showLogLevelDialog(tv: TextView) {
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
                refreshLogLevelDisplay(tv)
                dialog.dismiss()
                Toast.makeText(this, "日志等级: ${newLevel.tag}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --- Hosts ---
    private fun setupHostsSection() {
        val tvHostsStatus = findViewById<TextView>(R.id.tvHostsStatus)
        val btnHostsTest = findViewById<MaterialButton>(R.id.btnHostsTest)

        refreshHostsStatus(tvHostsStatus)

        btnHostsTest.setOnClickListener {
            startActivity(Intent(this, HostsTestActivity::class.java))
        }
    }

    private fun refreshHostsStatus(tv: TextView) {
        val lastTime = GitHubHostsHelper.getLastTestTime(this)
        if (lastTime > 0 && GitHubHostsHelper.isCacheValid(this)) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastTime))
            tv.text = "上次测试: $date (有效)"
            tv.setTextColor(getColor(R.color.accent))
        } else if (lastTime > 0) {
            tv.text = "上次测试结果已过期，建议重新测试"
            tv.setTextColor(getColor(R.color.text_secondary))
        } else {
            tv.text = "尚未测试"
            tv.setTextColor(getColor(R.color.text_secondary))
        }
    }

    // --- Version ---
    private fun setupVersionSection() {
        val updater = UpdateChecker(this)
        tvCurrentVersion.text = updater.getCurrentVersion()

        btnCheckUpdate.setOnClickListener {
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.text = "检查中..."
            tvUpdateStatus.visibility = View.VISIBLE
            tvUpdateStatus.text = "正在检查更新..."
            cardReleaseNotes.visibility = View.GONE

            updater.checkForUpdate { release ->
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = "检查更新"

                if (release != null) {
                    layoutLatestVersion.visibility = View.VISIBLE
                    tvLatestVersion.text = release.tagName
                    tvUpdateStatus.text = "发现新版本"
                    tvUpdateStatus.setTextColor(getColor(R.color.accent))

                    if (release.body.isNotBlank()) {
                        cardReleaseNotes.visibility = View.VISIBLE
                        tvReleaseNotes.text = release.body
                    }

                    updater.showUpdateDialog(release)
                } else {
                    layoutLatestVersion.visibility = View.GONE
                    tvUpdateStatus.text = "已是最新版本"
                    tvUpdateStatus.setTextColor(getColor(R.color.text_secondary))
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnDownloadManager).setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }
    }
}
