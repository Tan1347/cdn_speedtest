package org.tan.cdntest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadManagerActivity : AppCompatActivity(), DownloadListener {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvCurrentDir: TextView
    private lateinit var progressStorage: ProgressBar
    private lateinit var tvStorageInfo: TextView
    private lateinit var layoutActiveDownloads: View
    private lateinit var rvActiveDownloads: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var rvFiles: RecyclerView
    private lateinit var btnPauseAll: MaterialButton
    private lateinit var btnResumeAll: MaterialButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private lateinit var adapter: DownloadFileAdapter
    private val fileList = mutableListOf<FileItem>()
    private val selectedIndices = mutableSetOf<Int>()
    private var isDeleteMode = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private lateinit var activeAdapter: ActiveDownloadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_manager)

        toolbar = findViewById(R.id.toolbar)
        tvCurrentDir = findViewById(R.id.tvCurrentDir)
        progressStorage = findViewById(R.id.progressStorage)
        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        layoutActiveDownloads = findViewById(R.id.layoutActiveDownloads)
        rvActiveDownloads = findViewById(R.id.rvActiveDownloads)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvFiles = findViewById(R.id.rvFiles)
        btnPauseAll = findViewById(R.id.btnPauseAll)
        btnResumeAll = findViewById(R.id.btnResumeAll)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDelete = findViewById(R.id.btnDelete)

        toolbar.setNavigationOnClickListener { finish() }

        // Toolbar menu
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_mode -> {
                    enterDeleteMode()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, DownloadSettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        setupList()
        setupButtons()
        setupActiveDownloads()
        loadFiles()

        // Back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDeleteMode) {
                    exitDeleteMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
        startRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadEngine.removeListener(this)
    }

    // --- DownloadListener ---
    override fun onProgress(task: DownloadTask) {
        refreshActiveDownloads()
    }

    override fun onComplete(task: DownloadTask) {
        refreshActiveDownloads()
        loadFiles()
    }

    override fun onFailed(task: DownloadTask, error: String) {
        refreshActiveDownloads()
        Toast.makeText(this, "下载失败: ${task.fileName} - $error", Toast.LENGTH_SHORT).show()
    }

    override fun onCancelled(task: DownloadTask) {
        refreshActiveDownloads()
    }

    // --- Setup ---
    private fun setupList() {
        adapter = DownloadFileAdapter(
            items = fileList,
            selectedIndices = selectedIndices,
            isDeleteMode = false,
            onSelectionChanged = { updateButtonState() },
            onMore = { item, anchor -> showItemMenu(item, anchor) },
            onItemClick = { item -> openFile(item) }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter
    }

    private fun setupButtons() {
        btnPauseAll.setOnClickListener { DownloadEngine.pauseAll() }
        btnResumeAll.setOnClickListener { DownloadEngine.resumeAll(this) }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnDelete.setOnClickListener { deleteSelected() }
    }

    private fun setupActiveDownloads() {
        activeAdapter = ActiveDownloadAdapter(
            onPauseResume = { task ->
                when (task.status) {
                    DownloadStatus.RUNNING -> DownloadEngine.pause(task.url)
                    DownloadStatus.PAUSED -> DownloadEngine.resume(this, task.url)
                    else -> {}
                }
            },
            onCancel = { task -> DownloadEngine.cancel(task.url) }
        )
        rvActiveDownloads.layoutManager = LinearLayoutManager(this)
        rvActiveDownloads.adapter = activeAdapter
        DownloadEngine.addListener(this)
    }

    // --- Delete Mode ---
    private fun enterDeleteMode() {
        isDeleteMode = true
        adapter.isDeleteMode = true
        adapter.notifyDataSetChanged()
        btnSelectAll.visibility = View.VISIBLE
        btnDelete.visibility = View.VISIBLE
        btnPauseAll.visibility = View.GONE
        btnResumeAll.visibility = View.GONE
        toolbar.menu.findItem(R.id.action_delete_mode)?.isVisible = false
        toolbar.menu.findItem(R.id.action_settings)?.isVisible = false
        toolbar.title = "选择文件"
    }

    private fun exitDeleteMode() {
        isDeleteMode = false
        adapter.isDeleteMode = false
        selectedIndices.clear()
        adapter.notifyDataSetChanged()
        btnSelectAll.visibility = View.GONE
        btnDelete.visibility = View.GONE
        toolbar.menu.findItem(R.id.action_delete_mode)?.isVisible = true
        toolbar.menu.findItem(R.id.action_settings)?.isVisible = true
        toolbar.title = "下载管理"
        updateButtonState()
        updateActiveButtons(DownloadEngine.getActiveTasks())
    }

    private fun toggleSelectAll() {
        if (selectedIndices.size == fileList.size) {
            selectedIndices.clear()
        } else {
            selectedIndices.clear()
            fileList.indices.forEach { selectedIndices.add(it) }
        }
        adapter.notifyDataSetChanged()
        updateButtonState()
    }

    // --- File Operations ---
    private fun showItemMenu(item: FileItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_open -> { openFile(item); true }
                R.id.action_rename -> { renameFile(item); true }
                R.id.action_copy_link -> { copyLink(item); true }
                R.id.action_delete_single -> { deleteSingleFile(item); true }
                R.id.action_share -> { shareFile(item); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun openFile(item: FileItem) {
        if (!item.isFile) return
        val file = File(item.path)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        // 视频文件优先使用内置播放器
        if (FileItem.isVideoFile(item.name)) {
            val uri = Uri.fromFile(file)
            PreviewPlayerActivity.start(this, uri.toString(), "video", item.name)
            return
        }

        // 其他文件使用系统默认应用打开
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val mimeType = getMimeType(item.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开此文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameFile(item: FileItem) {
        val editText = EditText(this).apply {
            setText(item.name)
            val dotIndex = item.name.lastIndexOf('.')
            if (dotIndex > 0) setSelection(0, dotIndex) else setSelection(item.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != item.name) {
                    val oldFile = File(item.path)
                    val newFile = File(oldFile.parentFile, newName)
                    if (newFile.exists()) {
                        Toast.makeText(this, "文件名已存在", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (oldFile.renameTo(newFile)) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                if (item.url != null) {
                                    DownloadRecordStore.deleteByUrl(this@DownloadManagerActivity, item.url)
                                    DownloadRecordStore.add(this@DownloadManagerActivity, DownloadRecord(
                                        name = newName, url = item.url,
                                        path = newFile.absolutePath, size = newFile.length(),
                                        date = newFile.lastModified()
                                    ))
                                }
                            }
                            loadFiles()
                            Toast.makeText(this@DownloadManagerActivity, "已重命名", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyLink(item: FileItem) {
        val text = item.url ?: item.path
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", text))
        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSingleFile(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 ${item.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (item.isFile) {
                            File(item.path).delete()
                        }
                        if (item.url != null) {
                            DownloadRecordStore.deleteByUrl(this@DownloadManagerActivity, item.url)
                        }
                    }
                    loadFiles()
                    Toast.makeText(this@DownloadManagerActivity, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareFile(item: FileItem) {
        if (!item.isFile) return
        val file = File(item.path)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val mimeType = getMimeType(item.name)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享文件"))
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "avi", "flv", "wmv", "mov", "webm", "3gp" -> "video/*"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    // --- Data ---
    private fun loadFiles() {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                DownloadRecordStore.getAll(this@DownloadManagerActivity)
            }

            val dir = DownloadHelper.getDownloadDir(this@DownloadManagerActivity)
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

            tvCurrentDir.text = "目录: ${dir.absolutePath}"

            // 用局部列表构建，完成后一次性替换，避免多次调用导致数据叠加
            val newList = mutableListOf<FileItem>()
            files.forEach { file ->
                val record = records.find { it.path == file.absolutePath }
                    ?: records.find { it.name == file.name && it.path.endsWith("/${it.name}") }
                newList.add(FileItem.fromFile(file, record?.url))
            }

            fileList.clear()
            selectedIndices.clear()
            fileList.addAll(newList)
            adapter.notifyDataSetChanged()

            tvEmpty.visibility = if (fileList.isEmpty()) View.VISIBLE else View.GONE
            rvFiles.visibility = if (fileList.isEmpty()) View.GONE else View.VISIBLE
            updateButtonState()
            updateStorageInfo()
        }
    }

    private fun updateStorageInfo() {
        val info = DownloadHelper.getStorageInfo(this)
        val percent = if (info.total > 0) ((info.used.toFloat() / info.total) * 100).toInt() else 0
        progressStorage.progress = percent
        tvStorageInfo.text = "${DownloadHelper.formatFileSize(info.used)} / ${DownloadHelper.formatFileSize(info.total)}"
    }

    private fun updateButtonState() {
        btnDelete.isEnabled = selectedIndices.isNotEmpty()
        btnSelectAll.text = if (selectedIndices.size == fileList.size) "取消全选" else "全选"
    }

    // --- Active Downloads ---
    private fun startRefreshLoop() {
        refreshHandler.post(object : Runnable {
            override fun run() {
                refreshActiveDownloads()
                refreshHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun refreshActiveDownloads() {
        val activeTasks = DownloadEngine.getActiveTasks()
        layoutActiveDownloads.visibility = if (activeTasks.isNotEmpty()) View.VISIBLE else View.GONE
        activeAdapter.updateData(activeTasks)
        updateActiveButtons(activeTasks)
    }

    private fun updateActiveButtons(activeTasks: List<DownloadTask>) {
        if (isDeleteMode) return
        val hasRunning = activeTasks.any { it.status == DownloadStatus.RUNNING }
        val hasPaused = activeTasks.any { it.status == DownloadStatus.PAUSED }
        btnPauseAll.visibility = if (hasRunning) View.VISIBLE else View.GONE
        btnResumeAll.visibility = if (hasPaused) View.VISIBLE else View.GONE
    }

    private fun deleteSelected() {
        val indicesToDelete = selectedIndices.toList()
        lifecycleScope.launch {
            var deleted = 0
            withContext(Dispatchers.IO) {
                for (idx in indicesToDelete) {
                    if (idx >= fileList.size) continue
                    val item = fileList[idx]
                    if (item.isFile) {
                        val file = File(item.path)
                        if (file.exists() && file.delete()) {
                            deleted++
                            if (item.url != null) {
                                DownloadRecordStore.deleteByUrl(this@DownloadManagerActivity, item.url)
                            }
                        }
                    }
                }
            }
            Toast.makeText(this@DownloadManagerActivity, "已删除 $deleted 个文件", Toast.LENGTH_SHORT).show()
            exitDeleteMode()
            loadFiles()
        }
    }
}

// --- FileItem ---
data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val date: Long,
    val isFile: Boolean,
    val url: String? = null
) {
    companion object {
        fun fromFile(file: File, url: String? = null): FileItem {
            return FileItem(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                date = file.lastModified(),
                isFile = file.isFile,
                url = url
            )
        }

        fun formatInfo(item: FileItem): String {
            val sizeStr = DownloadHelper.formatFileSize(item.size)
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.date))
            return "$sizeStr  $dateStr"
        }

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp4", "ts", "mkv", "flv", "avi", "wmv", "mov", "webm", "3gp")
        }
    }
}

// --- DownloadFileAdapter ---
class DownloadFileAdapter(
    private val items: List<FileItem>,
    private val selectedIndices: MutableSet<Int>,
    var isDeleteMode: Boolean,
    private val onSelectionChanged: () -> Unit,
    private val onMore: (FileItem, View) -> Unit,
    private val onItemClick: (FileItem) -> Unit
) : RecyclerView.Adapter<DownloadFileAdapter.ViewHolder>() {

    private val thumbCache = LruCache<String, Bitmap>(30)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvInfo: TextView = view.findViewById(R.id.tvInfo)
        val btnMore: ImageButton = view.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvInfo.text = FileItem.formatInfo(item)

        // Checkbox visibility in delete mode
        holder.checkBox.visibility = if (isDeleteMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = selectedIndices.contains(position)

        holder.checkBox.setOnClickListener {
            if (selectedIndices.contains(position)) {
                selectedIndices.remove(position)
            } else {
                selectedIndices.add(position)
            }
            onSelectionChanged()
        }

        // Thumbnail
        if (FileItem.isVideoFile(item.name) && item.isFile) {
            holder.ivThumb.visibility = View.VISIBLE
            loadThumbnail(holder, item)
        } else {
            holder.ivThumb.visibility = View.GONE
        }

        // Click behavior
        holder.itemView.setOnClickListener {
            if (isDeleteMode) {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
                if (selectedIndices.contains(position)) {
                    selectedIndices.remove(position)
                } else {
                    selectedIndices.add(position)
                }
                onSelectionChanged()
            } else {
                onItemClick(item)
            }
        }

        // More button
        holder.btnMore.setOnClickListener { anchor ->
            onMore(item, anchor)
        }
    }

    override fun getItemCount() = items.size

    private fun loadThumbnail(holder: ViewHolder, item: FileItem) {
        val cached = thumbCache.get(item.path)
        if (cached != null) {
            holder.ivThumb.setImageBitmap(cached)
            return
        }

        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(item.path)
                val bitmap = retriever.getFrameAtTime(1_000_000)
                retriever.release()
                if (bitmap != null) {
                    thumbCache.put(item.path, bitmap)
                    holder.itemView.post { holder.ivThumb.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {}
        }.start()
    }
}
