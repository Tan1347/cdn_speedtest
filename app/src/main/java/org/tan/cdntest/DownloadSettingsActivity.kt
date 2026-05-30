package org.tan.cdntest

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.tan.cdntest.ui.theme.AppColors
import org.tan.cdntest.ui.theme.CDNTestTheme

class DownloadSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CDNTestTheme {
                DownloadSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun DownloadSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Player kernel state
    var kernelName by remember { mutableStateOf(getKernelDisplayName(DownloadHelper.getPlayerKernel(context))) }
    var showKernelDialog by remember { mutableStateOf(false) }

    // Download dir state
    var dirName by remember { mutableStateOf(getDirDisplayName(context)) }
    var showDirDialog by remember { mutableStateOf(false) }

    // Concurrent downloads state
    var concurrentText by remember { mutableStateOf("最多同时下载 ${DownloadHelper.getMaxConcurrentDownloads(context)} 个任务") }
    var showConcurrentDialog by remember { mutableStateOf(false) }

    fun refreshAll() {
        kernelName = getKernelDisplayName(DownloadHelper.getPlayerKernel(context))
        dirName = getDirDisplayName(context)
        concurrentText = "最多同时下载 ${DownloadHelper.getMaxConcurrentDownloads(context)} 个任务"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("软件设置", color = AppColors.white) },
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
            // 播放选项 card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "播放选项",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary()
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showKernelDialog = true }
                            .padding(top = 12.dp)
                    ) {
                        Text(
                            text = "播放器内核",
                            fontSize = 14.sp,
                            color = AppColors.textPrimary()
                        )
                        Text(
                            text = kernelName,
                            fontSize = 13.sp,
                            color = AppColors.textSecondary(),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = "* 播放器内核切换需要集成对应库，当前仅为设置项预留",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary(),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 下载位置 card
            SettingCard(
                title = "下载位置",
                summary = dirName,
                onClick = { showDirDialog = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 下载任务数 card
            SettingCard(
                title = "下载任务数",
                summary = concurrentText,
                onClick = { showConcurrentDialog = true }
            )
        }

        // --- Player kernel dialog ---
        if (showKernelDialog) {
            val items = listOf(
                "EXO 播放器 (快速解码 / 兼容性良好)",
                "IJK 播放器 (稳定 / 兼容性极佳)"
            )
            val currentKernel = DownloadHelper.getPlayerKernel(context)
            val checkedIndex = if (currentKernel == "ijk") 1 else 0

            SingleChoiceDialog(
                title = "播放器内核",
                items = items,
                checkedIndex = checkedIndex,
                onItemSelected = { which ->
                    showKernelDialog = false
                    val kernel = if (which == 0) "exo" else "ijk"
                    DownloadHelper.setPlayerKernel(context, kernel)
                    refreshAll()
                    Toast.makeText(context, "已切换到: ${items[which]}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showKernelDialog = false }
            )
        }

        // --- Download dir dialog ---
        if (showDirDialog) {
            val items = listOf(
                "内置存储 (应用内部目录)",
                "系统下载目录 (Download/cdntest)"
            )
            val isSystem = DownloadHelper.isSystemDir(context)
            val checkedIndex = if (isSystem) 1 else 0

            SingleChoiceDialog(
                title = "下载位置",
                items = items,
                checkedIndex = checkedIndex,
                onItemSelected = { which ->
                    showDirDialog = false
                    DownloadHelper.setUseSystemDir(context, which == 1)
                    refreshAll()
                    Toast.makeText(context, "已切换到: ${items[which]}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showDirDialog = false }
            )
        }

        // --- Concurrent downloads dialog ---
        if (showConcurrentDialog) {
            val items = listOf("1 个任务", "2 个任务", "3 个任务")
            val currentCount = DownloadHelper.getMaxConcurrentDownloads(context)
            val checkedIndex = (currentCount - 1).coerceIn(0, 2)

            SingleChoiceDialog(
                title = "最大同时下载任务数",
                items = items,
                checkedIndex = checkedIndex,
                onItemSelected = { which ->
                    showConcurrentDialog = false
                    DownloadHelper.setMaxConcurrentDownloads(context, which + 1)
                    refreshAll()
                    Toast.makeText(context, "已设置为: ${items[which]}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showConcurrentDialog = false }
            )
        }
    }
}

private fun getKernelDisplayName(kernel: String): String = when (kernel) {
    "exo" -> "EXO 播放器 (快速解码 / 兼容性良好)"
    "ijk" -> "IJK 播放器 (稳定 / 兼容性极佳)"
    else -> "EXO 播放器 (快速解码 / 兼容性良好)"
}

private fun getDirDisplayName(context: android.content.Context): String {
    val isSystem = DownloadHelper.isSystemDir(context)
    val path = DownloadHelper.getDownloadDir(context).absolutePath
    return if (isSystem) {
        "系统下载目录 (Download/cdntest)\n$path"
    } else {
        "内置存储 (应用内部目录)\n$path"
    }
}

@Composable
private fun SettingCard(title: String, summary: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp)
        ) {
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
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    items: List<String>,
    checkedIndex: Int,
    onItemSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(items) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemSelected(index) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == checkedIndex,
                                onClick = { onItemSelected(index) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = item, fontSize = 15.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            }
        }
    }
}
