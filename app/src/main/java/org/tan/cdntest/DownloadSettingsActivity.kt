package org.tan.cdntest

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class DownloadSettingsActivity : AppCompatActivity() {

    private lateinit var tvPlayerKernel: TextView
    private lateinit var tvDownloadDir: TextView
    private lateinit var tvConcurrentCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvPlayerKernel = findViewById(R.id.tvPlayerKernel)
        tvDownloadDir = findViewById(R.id.tvDownloadDir)
        tvConcurrentCount = findViewById(R.id.tvConcurrentCount)

        refreshDisplays()

        findViewById<android.view.View>(R.id.layoutPlayerKernel).setOnClickListener {
            showPlayerKernelDialog()
        }
        findViewById<android.view.View>(R.id.layoutDownloadDir).setOnClickListener {
            showDownloadDirDialog()
        }
        findViewById<android.view.View>(R.id.layoutConcurrent).setOnClickListener {
            showConcurrentDialog()
        }
    }

    private fun refreshDisplays() {
        // 播放器内核
        val kernel = DownloadHelper.getPlayerKernel(this)
        tvPlayerKernel.text = when (kernel) {
            "exo" -> "EXO 播放器 (快速解码 / 兼容性良好)"
            "ijk" -> "IJK 播放器 (稳定 / 兼容性极佳)"
            else -> "EXO 播放器 (快速解码 / 兼容性良好)"
        }

        // 下载位置
        val isSystem = DownloadHelper.isSystemDir(this)
        tvDownloadDir.text = if (isSystem) {
            "系统下载目录 (Download/cdntest)\n${DownloadHelper.getDownloadDir(this).absolutePath}"
        } else {
            "内置存储 (应用内部目录)\n${DownloadHelper.getDownloadDir(this).absolutePath}"
        }

        // 下载任务数
        val count = DownloadHelper.getMaxConcurrentDownloads(this)
        tvConcurrentCount.text = "最多同时下载 $count 个任务"
    }

    private fun showPlayerKernelDialog() {
        val items = arrayOf(
            "EXO 播放器 (快速解码 / 兼容性良好)",
            "IJK 播放器 (稳定 / 兼容性极佳)"
        )
        val currentKernel = DownloadHelper.getPlayerKernel(this)
        val checkedIndex = if (currentKernel == "ijk") 1 else 0

        AlertDialog.Builder(this)
            .setTitle("播放器内核")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val kernel = if (which == 0) "exo" else "ijk"
                DownloadHelper.setPlayerKernel(this, kernel)
                refreshDisplays()
                dialog.dismiss()
                Toast.makeText(this, "已切换到: ${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDownloadDirDialog() {
        val items = arrayOf(
            "内置存储 (应用内部目录)",
            "系统下载目录 (Download/cdntest)"
        )
        val isSystem = DownloadHelper.isSystemDir(this)
        val checkedIndex = if (isSystem) 1 else 0

        AlertDialog.Builder(this)
            .setTitle("下载位置")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val useSystem = which == 1
                DownloadHelper.setUseSystemDir(this, useSystem)
                refreshDisplays()
                dialog.dismiss()
                Toast.makeText(this, "已切换到: ${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showConcurrentDialog() {
        val items = arrayOf(
            "1 个任务",
            "2 个任务",
            "3 个任务"
        )
        val currentCount = DownloadHelper.getMaxConcurrentDownloads(this)
        val checkedIndex = (currentCount - 1).coerceIn(0, 2)

        AlertDialog.Builder(this)
            .setTitle("最大同时下载任务数")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val count = which + 1
                DownloadHelper.setMaxConcurrentDownloads(this, count)
                refreshDisplays()
                dialog.dismiss()
                Toast.makeText(this, "已设置为: ${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
