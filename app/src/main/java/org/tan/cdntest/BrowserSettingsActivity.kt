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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.tan.cdntest.ui.theme.AppColors
import org.tan.cdntest.ui.theme.CDNTestTheme

class BrowserSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CDNTestTheme {
                BrowserSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun BrowserSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // UA state
    var uaName by remember { mutableStateOf(UserAgentHelper.getCurrentUaName(context)) }
    var showUaDialog by remember { mutableStateOf(false) }
    var showAddUaDialog by remember { mutableStateOf(false) }
    var showManageUaDialog by remember { mutableStateOf(false) }

    // Search engine state
    var engineName by remember { mutableStateOf(SearchEngineHelper.getCurrentEngine(context).name) }
    var showEngineDialog by remember { mutableStateOf(false) }
    var showAddEngineDialog by remember { mutableStateOf(false) }

    fun refreshUa() {
        uaName = UserAgentHelper.getCurrentUaName(context)
    }

    fun refreshEngine() {
        engineName = SearchEngineHelper.getCurrentEngine(context).name
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("浏览器设置", color = AppColors.white) },
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
            // Browser UA card
            SettingCard(
                title = "浏览器标识",
                summary = "当前: $uaName",
                onClick = { showUaDialog = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Search engine card
            SettingCard(
                title = "搜索引擎",
                summary = "当前: $engineName",
                onClick = { showEngineDialog = true }
            )
        }

        // --- UA selection dialog ---
        if (showUaDialog) {
            val presets = UserAgentHelper.presets
            val customList = UserAgentHelper.getCustomList(context)
            val currentUa = UserAgentHelper.getCurrentUa(context)
            val allItems = mutableListOf<String>()
            allItems.addAll(presets.map { it.name })
            allItems.addAll(customList.map { "[自定义] ${it.name}" })
            allItems.add("+ 添加自定义标识")
            val checkedIndex = presets.indexOfFirst { it.ua == currentUa }.takeIf { it >= 0 }
                ?: (presets.size + customList.indexOfFirst { it.ua == currentUa }).takeIf { it >= presets.size }
                ?: 0

            SingleChoiceDialog(
                title = "切换浏览器标识",
                items = allItems,
                checkedIndex = checkedIndex,
                onItemSelected = { which ->
                    showUaDialog = false
                    when {
                        which < presets.size -> {
                            UserAgentHelper.setCurrentUa(context, presets[which].ua)
                            refreshUa()
                            Toast.makeText(context, "已切换: ${UserAgentHelper.getCurrentUaName(context)}", Toast.LENGTH_SHORT).show()
                        }
                        which < presets.size + customList.size -> {
                            UserAgentHelper.setCurrentUa(context, customList[which - presets.size].ua)
                            refreshUa()
                            Toast.makeText(context, "已切换: ${customList[which - presets.size].name}", Toast.LENGTH_SHORT).show()
                        }
                        else -> { showAddUaDialog = true }
                    }
                },
                onDismiss = { showUaDialog = false },
                neutralButtonText = "管理自定义",
                onNeutral = {
                    showUaDialog = false
                    showManageUaDialog = true
                }
            )
        }

        // --- Add custom UA dialog ---
        if (showAddUaDialog) {
            AddCustomDialog(
                title = "添加自定义标识",
                nameHint = "名称（如：我的浏览器）",
                valueHint = "User-Agent 字符串",
                onConfirm = { name, value ->
                    showAddUaDialog = false
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        UserAgentHelper.addCustom(context, name, value)
                        UserAgentHelper.setCurrentUa(context, value)
                        refreshUa()
                        Toast.makeText(context, "已切换: $name", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "名称和标识不能为空", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showAddUaDialog = false }
            )
        }

        // --- Manage custom UA dialog ---
        if (showManageUaDialog) {
            val customList = UserAgentHelper.getCustomList(context)
            if (customList.isEmpty()) {
                showManageUaDialog = false
                Toast.makeText(context, "暂无自定义标识", Toast.LENGTH_SHORT).show()
            } else {
                DeleteListDialog(
                    title = "删除自定义标识",
                    items = customList.map { it.name },
                    onItemClick = { which ->
                        showManageUaDialog = false
                        UserAgentHelper.removeCustom(context, customList[which].ua)
                        Toast.makeText(context, "已删除: ${customList[which].name}", Toast.LENGTH_SHORT).show()
                        refreshUa()
                    },
                    onDismiss = { showManageUaDialog = false }
                )
            }
        }

        // --- Search engine selection dialog ---
        if (showEngineDialog) {
            val presets = SearchEngineHelper.presets
            val currentIndex = SearchEngineHelper.getCurrentIndex(context)
            val allItems = mutableListOf<String>()
            allItems.addAll(presets.map { it.name })
            allItems.add("+ 自定义搜索引擎")

            SingleChoiceDialog(
                title = "切换搜索引擎",
                items = allItems,
                checkedIndex = currentIndex,
                onItemSelected = { which ->
                    showEngineDialog = false
                    if (which < presets.size) {
                        SearchEngineHelper.setCurrentEngine(context, which)
                        refreshEngine()
                        Toast.makeText(context, "已切换到: ${presets[which].name}", Toast.LENGTH_SHORT).show()
                    } else {
                        showAddEngineDialog = true
                    }
                },
                onDismiss = { showEngineDialog = false }
            )
        }

        // --- Add custom search engine dialog ---
        if (showAddEngineDialog) {
            AddCustomDialog(
                title = "自定义搜索引擎",
                nameHint = "搜索引擎名称",
                valueHint = "搜索URL (用 %s 代替关键词)",
                onConfirm = { name, url ->
                    showAddEngineDialog = false
                    if (name.isNotEmpty() && url.isNotEmpty() && url.contains("%s")) {
                        SearchEngineHelper.setCustomEngine(context, name, url)
                        refreshEngine()
                        Toast.makeText(context, "已切换到: $name", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "URL 中必须包含 %s 作为关键词占位符", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showAddEngineDialog = false }
            )
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
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
    onDismiss: () -> Unit,
    neutralButtonText: String? = null,
    onNeutral: (() -> Unit)? = null
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
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
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
                    if (neutralButtonText != null && onNeutral != null) {
                        TextButton(onClick = onNeutral) {
                            Text(neutralButtonText)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCustomDialog(
    title: String,
    nameHint: String,
    valueHint: String,
    onConfirm: (name: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(nameHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(valueHint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    TextButton(onClick = { onConfirm(name.trim(), value.trim()) }) {
                        Text("添加")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteListDialog(
    title: String,
    items: List<String>,
    onItemClick: (Int) -> Unit,
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
                        Text(
                            text = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(index) }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            fontSize = 15.sp
                        )
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
