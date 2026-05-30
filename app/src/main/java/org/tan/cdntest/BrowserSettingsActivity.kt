package org.tan.cdntest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class BrowserSettingsActivity : AppCompatActivity() {

    private lateinit var tvCurrentUa: TextView
    private lateinit var tvCurrentEngine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvCurrentUa = findViewById(R.id.tvCurrentUa)
        tvCurrentEngine = findViewById(R.id.tvCurrentEngine)

        findViewById<View>(R.id.layoutUa).setOnClickListener { showUaDialog() }
        findViewById<View>(R.id.layoutSearchEngine).setOnClickListener { showEngineDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshUaDisplay()
        refreshEngineDisplay()
    }

    // --- UA ---
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
}
