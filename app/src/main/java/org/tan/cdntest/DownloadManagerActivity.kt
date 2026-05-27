package org.tan.cdntest

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadManagerActivity : AppCompatActivity(), DownloadListener {

    private lateinit var rvFiles: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnPauseAll: MaterialButton
    private lateinit var btnResumeAll: MaterialButton
    private lateinit var chipGroupSource: ChipGroup
    private lateinit var tvCurrentDir: TextView
    private lateinit var progressStorage: ProgressBar
    private lateinit var tvStorageInfo: TextView
    private lateinit var layoutActiveDownloads: View
    private lateinit var rvActiveDownloads: RecyclerView

    private lateinit var adapter: DownloadFileAdapter
    private lateinit var activeAdapter: ActiveDownloadAdapter
    private val fileList = mutableListOf<FileItem>()
    private val selectedIndices = mutableSetOf<Int>()
    private var currentSource = 0 // 0=app dir, 1=system dir, 2=system DM

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshActiveDownloads()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rvFiles = findViewById(R.id.rvFiles)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnPauseAll = findViewById(R.id.btnPauseAll)
        btnResumeAll = findViewById(R.id.btnResumeAll)
        chipGroupSource = findViewById(R.id.chipGroupSource)
        tvCurrentDir = findViewById(R.id.tvCurrentDir)
        progressStorage = findViewById(R.id.progressStorage)
        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        layoutActiveDownloads = findViewById(R.id.layoutActiveDownloads)
        rvActiveDownloads = findViewById(R.id.rvActiveDownloads)

        setupChipSource()
        setupList()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        DownloadEngine.addListener(this)
        handler.post(refreshRunnable)
        loadFiles()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        DownloadEngine.removeListener(this)
        super.onPause()
    }

    private fun setupChipSource() {
        val savedSource = when {
            DownloadHelper.isSystemDir(this) -> R.id.chipSystemDir
            else -> R.id.chipAppDir
        }
        chipGroupSource.check(savedSource)
        currentSource = when (savedSource) {
            R.id.chipSystemDir -> 1
            R.id.chipSystemDm -> 2
            else -> 0
        }

        chipGroupSource.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                currentSource = when (checkedIds[0]) {
                    R.id.chipAppDir -> { DownloadHelper.setUseSystemDir(this, false); 0 }
                    R.id.chipSystemDir -> { DownloadHelper.setUseSystemDir(this, true); 1 }
                    R.id.chipSystemDm -> 2
                    else -> 0
                }
                selectedIndices.clear()
                loadFiles()
            }
        }
    }

    private fun setupList() {
        // Active downloads adapter
        activeAdapter = ActiveDownloadAdapter(
            items = emptyList(),
            onPauseResume = { task ->
                if (task.status == DownloadStatus.RUNNING) {
                    DownloadEngine.pause(task.url)
                } else if (task.status == DownloadStatus.PAUSED) {
                    DownloadEngine.resume(this, task.url)
                }
                refreshActiveDownloads()
            },
            onCancel = { task ->
                AlertDialog.Builder(this)
                    .setTitle("取消下载")
                    .setMessage("确定取消下载 ${task.fileName}？")
                    .setPositiveButton("取消下载") { _, _ ->
                        DownloadEngine.cancel(task.url)
                        refreshActiveDownloads()
                    }
                    .setNegativeButton("返回", null)
                    .show()
            }
        )
        rvActiveDownloads.layoutManager = LinearLayoutManager(this)
        rvActiveDownloads.adapter = activeAdapter

        // Completed files adapter
        adapter = DownloadFileAdapter(
            items = fileList,
            selectedIndices = selectedIndices,
            onSelectionChanged = { updateButtonState() },
            onCopy = { item ->
                val text = item.url ?: item.path
                val label = if (item.url != null) "已复制链接" else "已复制路径"
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", text))
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter
    }

    private fun setupButtons() {
        btnDelete.setOnClickListener {
            if (selectedIndices.isEmpty()) {
                Toast.makeText(this, "请先选择要删除的文件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除选中的 ${selectedIndices.size} 个文件吗？")
                .setPositiveButton("删除") { _, _ -> deleteSelected() }
                .setNegativeButton("取消", null)
                .show()
        }

        btnSelectAll.setOnClickListener {
            if (selectedIndices.size == fileList.size) {
                selectedIndices.clear()
            } else {
                selectedIndices.addAll(fileList.indices)
            }
            adapter.notifyDataSetChanged()
            updateButtonState()
        }

        btnPauseAll.setOnClickListener {
            DownloadEngine.pauseAll()
            refreshActiveDownloads()
        }

        btnResumeAll.setOnClickListener {
            DownloadEngine.resumeAll(this)
            refreshActiveDownloads()
        }
    }

    private fun loadFiles() {
        fileList.clear()
        selectedIndices.clear()
        adapter.notifyDataSetChanged()

        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                DownloadRecordStore.getAll(this@DownloadManagerActivity)
            }

            when (currentSource) {
                0, 1 -> {
                    val files = DownloadHelper.getDownloadedFiles(this@DownloadManagerActivity)
                    tvCurrentDir.text = "目录: ${DownloadHelper.getDownloadDir(this@DownloadManagerActivity).absolutePath}"
                    files.forEach { file ->
                        val record = records.find { it.path == file.absolutePath || it.name == file.name }
                        fileList.add(FileItem.fromFile(file, record?.url))
                    }
                    btnSelectAll.visibility = View.VISIBLE
                }
                2 -> {
                    val downloads = DownloadHelper.getSystemDownloadManagerFiles(this@DownloadManagerActivity)
                    tvCurrentDir.text = "来源: 系统下载管理器"
                    downloads.forEach { fileList.add(FileItem.fromSystemDownload(it)) }
                    btnSelectAll.visibility = View.VISIBLE
                }
            }

            adapter.notifyDataSetChanged()
            tvEmpty.visibility = if (fileList.isEmpty()) View.VISIBLE else View.GONE
            rvFiles.visibility = if (fileList.isEmpty()) View.GONE else View.VISIBLE
            updateButtonState()
            updateStorageInfo()
        }
    }

    private fun updateStorageInfo() {
        try {
            val info = DownloadHelper.getStorageInfo(this)
            val percent = if (info.total > 0) (info.used * 100 / info.total).toInt() else 0
            progressStorage.progress = percent
            tvStorageInfo.text = "已用 ${DownloadHelper.formatFileSize(info.used)} / 共 ${DownloadHelper.formatFileSize(info.total)} (可用 ${DownloadHelper.formatFileSize(info.available)})"
        } catch (_: Exception) {
            tvStorageInfo.text = "无法获取存储信息"
        }
    }

    private fun refreshActiveDownloads() {
        val activeTasks = DownloadEngine.getActiveTasks()
        if (activeTasks.isEmpty()) {
            layoutActiveDownloads.visibility = View.GONE
        } else {
            layoutActiveDownloads.visibility = View.VISIBLE
            activeAdapter.updateData(activeTasks)
        }
        updateActiveButtons(activeTasks)
    }

    private fun updateActiveButtons(activeTasks: List<DownloadTask>) {
        val hasRunning = activeTasks.any { it.status == DownloadStatus.RUNNING }
        val hasPaused = activeTasks.any { it.status == DownloadStatus.PAUSED }
        btnPauseAll.visibility = if (hasRunning) View.VISIBLE else View.GONE
        btnResumeAll.visibility = if (hasPaused) View.VISIBLE else View.GONE
    }

    private fun deleteSelected() {
        var deleted = 0
        for (idx in selectedIndices) {
            val item = fileList[idx]
            if (item.isFile) {
                val file = File(item.path)
                if (file.exists() && file.delete()) deleted++
            }
        }
        Toast.makeText(this, "已删除 $deleted 个文件", Toast.LENGTH_SHORT).show()
        selectedIndices.clear()
        loadFiles()
    }

    private fun updateButtonState() {
        btnDelete.isEnabled = selectedIndices.isNotEmpty()
        btnDelete.text = if (selectedIndices.isEmpty()) "删除" else "删除 (${selectedIndices.size})"
        btnSelectAll.text = if (selectedIndices.size == fileList.size && fileList.isNotEmpty()) "取消全选" else "全选"
    }

    // DownloadListener callbacks
    override fun onProgress(task: DownloadTask) {
        refreshActiveDownloads()
    }

    override fun onComplete(task: DownloadTask) {
        refreshActiveDownloads()
        loadFiles()
    }

    override fun onFailed(task: DownloadTask, error: String) {
        refreshActiveDownloads()
        Toast.makeText(this, "${task.fileName} 下载失败: $error", Toast.LENGTH_SHORT).show()
    }

    override fun onCancelled(task: DownloadTask) {
        refreshActiveDownloads()
    }
}

data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val date: Long,
    val isFile: Boolean,
    val url: String? = null
) {
    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun fromFile(file: File, url: String? = null) = FileItem(
            name = file.name,
            path = file.absolutePath,
            size = file.length(),
            date = file.lastModified(),
            isFile = true,
            url = url
        )

        fun fromSystemDownload(dl: SystemDownload) = FileItem(
            name = dl.title,
            path = dl.localUri,
            size = dl.size,
            date = dl.date,
            isFile = false
        )

        fun formatInfo(item: FileItem): String {
            val sizeStr = DownloadHelper.formatFileSize(item.size)
            val dateStr = dateFormat.format(Date(item.date))
            return "$sizeStr  $dateStr"
        }

        private val VIDEO_EXTENSIONS = setOf("mp4", "ts", "mkv", "flv", "avi", "wmv", "mov", "webm", "3gp")

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }
}

class DownloadFileAdapter(
    private val items: List<FileItem>,
    private val selectedIndices: MutableSet<Int>,
    private val onSelectionChanged: () -> Unit,
    private val onCopy: (FileItem) -> Unit
) : RecyclerView.Adapter<DownloadFileAdapter.ViewHolder>() {

    private val thumbCache = LruCache<String, Bitmap>(30)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvInfo: TextView = view.findViewById(R.id.tvInfo)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvInfo.text = FileItem.formatInfo(item)

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selectedIndices.contains(position)

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIndices.add(position) else selectedIndices.remove(position)
            onSelectionChanged()
        }

        holder.btnCopy.setOnClickListener { onCopy(item) }
        holder.itemView.setOnClickListener {
            holder.checkBox.isChecked = !holder.checkBox.isChecked
        }

        // Thumbnail for video files
        if (item.isFile && FileItem.isVideoFile(item.name)) {
            val cached = thumbCache.get(item.path)
            if (cached != null) {
                holder.ivThumb.setImageBitmap(cached)
                holder.ivThumb.visibility = View.VISIBLE
            } else {
                holder.ivThumb.setImageResource(android.R.color.darker_gray)
                holder.ivThumb.visibility = View.VISIBLE
                loadThumbnail(item.path, holder.ivThumb)
            }
        } else {
            holder.ivThumb.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size

    private fun loadThumbnail(path: String, imageView: ImageView) {
        imageView.tag = path
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, 96, 96, true)
                    if (scaled !== frame) frame.recycle()
                    thumbCache.put(path, scaled)
                    imageView.post {
                        if (imageView.tag == path) {
                            imageView.setImageBitmap(scaled)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }
}
