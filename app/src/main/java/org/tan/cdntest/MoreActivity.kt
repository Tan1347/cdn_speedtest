package org.tan.cdntest

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MoreActivity : AppCompatActivity() {

    private lateinit var tvCurrentUa: TextView
    private lateinit var tvCurrentDir: TextView
    private lateinit var rgDownloadDir: RadioGroup
    private lateinit var tvCurrentEngine: TextView
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

        tvCurrentUa = findViewById(R.id.tvCurrentUa)
        tvCurrentDir = findViewById(R.id.tvCurrentDir)
        rgDownloadDir = findViewById(R.id.rgDownloadDir)
        tvCurrentEngine = findViewById(R.id.tvCurrentEngine)
        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        layoutLatestVersion = findViewById(R.id.layoutLatestVersion)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        cardReleaseNotes = findViewById(R.id.cardReleaseNotes)
        tvReleaseNotes = findViewById(R.id.tvReleaseNotes)

        setupUaSection()
        setupSearchEngineSection()
        setupLogSection()
        setupDownloadDirSection()
        setupTsFormatSection()
        setupHostsSection()
        setupVersionSection()
    }

    override fun onResume() {
        super.onResume()
        refreshUaDisplay()
        refreshDirDisplay()
        refreshEngineDisplay()
        refreshHostsStatus(findViewById(R.id.tvHostsStatus))
    }

    // --- UA ---
    private fun setupUaSection() {
        refreshUaDisplay()
        findViewById<MaterialButton>(R.id.btnChangeUa).setOnClickListener {
            showUaDialog()
        }
    }

    private fun refreshUaDisplay() {
        tvCurrentUa.text = "当前: ${UserAgentHelper.getCurrentUaName(this)}"
    }

    private fun showUaDialog() {
        val presets = UserAgentHelper.presets
        val customList = UserAgentHelper.getCustomList(this)
        val currentUa = UserAgentHelper.getCurrentUa(this)
        val allItems = mutableListOf<String>()
        allItems.addAll(presets.map { it.name })
        allItems.addAll(customList.map { "[自定义] ${it.name}" })
        allItems.add("+ 添加自定义标识")
        val checkedIndex = presets.indexOfFirst { it.ua == currentUa }.takeIf { it >= 0 }
            ?: (presets.size + customList.indexOfFirst { it.ua == currentUa }).takeIf { it >= presets.size } ?: 0

        AlertDialog.Builder(this)
            .setTitle("切换浏览器标识")
            .setSingleChoiceItems(allItems.toTypedArray(), checkedIndex) { dialog, which ->
                dialog.dismiss()
                when {
                    which < presets.size -> applyUa(presets[which].ua)
                    which < presets.size + customList.size -> applyUa(customList[which - presets.size].ua)
                    else -> showAddCustomUaDialog()
                }
            }
            .setNeutralButton("管理自定义") { _, _ -> showManageCustomUaDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyUa(ua: String) {
        UserAgentHelper.setCurrentUa(this, ua)
        refreshUaDisplay()
        Toast.makeText(this, "已切换: ${UserAgentHelper.getCurrentUaName(this)}", Toast.LENGTH_SHORT).show()
    }

    private fun showAddCustomUaDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_ua, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etUa = view.findViewById<EditText>(R.id.etUa)
        AlertDialog.Builder(this)
            .setTitle("添加自定义标识")
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().trim()
                val ua = etUa.text.toString().trim()
                if (name.isNotEmpty() && ua.isNotEmpty()) {
                    UserAgentHelper.addCustom(this, name, ua)
                    applyUa(ua)
                } else {
                    Toast.makeText(this, "名称和标识不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManageCustomUaDialog() {
        val customList = UserAgentHelper.getCustomList(this)
        if (customList.isEmpty()) {
            Toast.makeText(this, "暂无自定义标识", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除自定义标识")
            .setItems(customList.map { it.name }.toTypedArray()) { _, which ->
                UserAgentHelper.removeCustom(this, customList[which].ua)
                Toast.makeText(this, "已删除: ${customList[which].name}", Toast.LENGTH_SHORT).show()
                refreshUaDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --- Search Engine ---
    private fun setupSearchEngineSection() {
        refreshEngineDisplay()
        findViewById<MaterialButton>(R.id.btnChangeEngine).setOnClickListener {
            showEngineDialog()
        }
    }

    private fun refreshEngineDisplay() {
        val engine = SearchEngineHelper.getCurrentEngine(this)
        tvCurrentEngine.text = "当前: ${engine.name}"
    }

    private fun showEngineDialog() {
        val presets = SearchEngineHelper.presets
        val currentIndex = SearchEngineHelper.getCurrentIndex(this)
        val allItems = mutableListOf<String>()
        allItems.addAll(presets.map { it.name })
        allItems.add("+ 自定义搜索引擎")

        AlertDialog.Builder(this)
            .setTitle("切换搜索引擎")
            .setSingleChoiceItems(allItems.toTypedArray(), currentIndex) { dialog, which ->
                dialog.dismiss()
                if (which < presets.size) {
                    SearchEngineHelper.setCurrentEngine(this, which)
                    refreshEngineDisplay()
                    Toast.makeText(this, "已切换到: ${presets[which].name}", Toast.LENGTH_SHORT).show()
                } else {
                    showAddCustomEngineDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCustomEngineDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_ua, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etUa = view.findViewById<EditText>(R.id.etUa)
        etName.hint = "搜索引擎名称"
        etUa.hint = "搜索URL (用 %s 代替关键词)"
        AlertDialog.Builder(this)
            .setTitle("自定义搜索引擎")
            .setView(view)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUa.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty() && url.contains("%s")) {
                    SearchEngineHelper.setCustomEngine(this, name, url)
                    refreshEngineDisplay()
                    Toast.makeText(this, "已切换到: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "URL 中必须包含 %s 作为关键词占位符", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --- Log Settings ---
    private fun setupLogSection() {
        val switchLog = findViewById<SwitchMaterial>(R.id.switchLog)
        val layoutLogLevel = findViewById<View>(R.id.layoutLogLevel)
        val rgLogLevel = findViewById<RadioGroup>(R.id.rgLogLevel)
        val tvLogDir = findViewById<TextView>(R.id.tvLogDir)

        val enabled = AppLogger.isEnabled(this)
        switchLog.isChecked = enabled
        layoutLogLevel.visibility = if (enabled) View.VISIBLE else View.GONE

        switchLog.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            AppLogger.setEnabled(this, isChecked)
            layoutLogLevel.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val level = AppLogger.getLevel(this)
        rgLogLevel.check(when (level) {
            AppLogger.Level.DEBUG -> R.id.rbDebug
            AppLogger.Level.INFO -> R.id.rbInfo
            AppLogger.Level.WARN -> R.id.rbWarn
            AppLogger.Level.ERROR -> R.id.rbError
        })

        rgLogLevel.setOnCheckedChangeListener { _, checkedId ->
            val newLevel = when (checkedId) {
                R.id.rbDebug -> AppLogger.Level.DEBUG
                R.id.rbInfo -> AppLogger.Level.INFO
                R.id.rbWarn -> AppLogger.Level.WARN
                R.id.rbError -> AppLogger.Level.ERROR
                else -> AppLogger.Level.INFO
            }
            AppLogger.setLevel(this, newLevel)
            Toast.makeText(this, "日志等级: ${newLevel.tag}", Toast.LENGTH_SHORT).show()
        }

        val logDir = java.io.File(getExternalFilesDir(null), "log")
        tvLogDir.text = "日志目录: ${logDir.absolutePath}"
    }

    // --- Download Directory ---
    private fun setupDownloadDirSection() {
        val isSystem = DownloadHelper.isSystemDir(this)
        rgDownloadDir.check(if (isSystem) R.id.rbSystemDir else R.id.rbAppDir)
        refreshDirDisplay()

        rgDownloadDir.setOnCheckedChangeListener { _, checkedId ->
            val useSystem = checkedId == R.id.rbSystemDir
            DownloadHelper.setUseSystemDir(this, useSystem)
            refreshDirDisplay()
            Toast.makeText(this, if (useSystem) "已切换到系统下载目录" else "已切换到应用内部目录", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshDirDisplay() {
        tvCurrentDir.text = "当前: ${DownloadHelper.getDownloadDir(this).absolutePath}"
    }

    // --- TS Format ---
    private fun setupTsFormatSection() {
        val rgTsFormat = findViewById<RadioGroup>(R.id.rgTsFormat)
        val current = DownloadHelper.getTsFormat(this)
        rgTsFormat.check(when (current) {
            TsOutputFormat.ORIGINAL -> R.id.rbTsOriginal
            TsOutputFormat.MP4 -> R.id.rbTsMp4
            TsOutputFormat.H264 -> R.id.rbTsH264
            TsOutputFormat.HEVC -> R.id.rbTsHevc
            TsOutputFormat.AV1 -> R.id.rbTsAv1
        })

        rgTsFormat.setOnCheckedChangeListener { _, checkedId ->
            val format = when (checkedId) {
                R.id.rbTsOriginal -> TsOutputFormat.ORIGINAL
                R.id.rbTsMp4 -> TsOutputFormat.MP4
                R.id.rbTsH264 -> TsOutputFormat.H264
                R.id.rbTsHevc -> TsOutputFormat.HEVC
                R.id.rbTsAv1 -> TsOutputFormat.AV1
                else -> TsOutputFormat.ORIGINAL
            }
            DownloadHelper.setTsFormat(this, format)
            Toast.makeText(this, "TS 格式: ${format.label}", Toast.LENGTH_SHORT).show()
        }

        val tvThreadCount = findViewById<TextView>(R.id.tvThreadCount)
        val seekBar = findViewById<SeekBar>(R.id.seekBarThreads)
        val currentThreads = DownloadHelper.getDownloadThreads(this)
        seekBar.progress = currentThreads
        tvThreadCount.text = "${currentThreads} 线程"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val threads = progress.coerceIn(1, 8)
                tvThreadCount.text = "${threads} 线程"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val threads = seekBar.progress.coerceIn(1, 8)
                DownloadHelper.setDownloadThreads(this@MoreActivity, threads)
            }
        })
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
