package org.tan.cdntest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadManagerActivity : AppCompatActivity() {

    private lateinit var rvFiles: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnSelectAll: MaterialButton

    private lateinit var adapter: DownloadFileAdapter
    private var files = mutableListOf<File>()
    private val selectedFiles = mutableSetOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rvFiles = findViewById(R.id.rvFiles)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)

        setupList()
        setupButtons()
        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    private fun setupList() {
        adapter = DownloadFileAdapter(
            files = files,
            selectedFiles = selectedFiles,
            onSelectionChanged = { updateButtonState() },
            onCopy = { file ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", file.absolutePath))
                Toast.makeText(this, "已复制文件路径", Toast.LENGTH_SHORT).show()
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter
    }

    private fun setupButtons() {
        btnDelete.setOnClickListener {
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, "请先选择要删除的文件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除选中的 ${selectedFiles.size} 个文件吗？")
                .setPositiveButton("删除") { _, _ -> deleteSelected() }
                .setNegativeButton("取消", null)
                .show()
        }

        btnSelectAll.setOnClickListener {
            if (selectedFiles.size == files.size) {
                selectedFiles.clear()
            } else {
                selectedFiles.addAll(files)
            }
            adapter.notifyDataSetChanged()
            updateButtonState()
        }
    }

    private fun loadFiles() {
        files.clear()
        files.addAll(DownloadHelper.getDownloadedFiles(this))
        selectedFiles.clear()
        adapter.notifyDataSetChanged()

        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        rvFiles.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        updateButtonState()
    }

    private fun deleteSelected() {
        var deleted = 0
        for (file in selectedFiles) {
            if (file.exists() && file.delete()) deleted++
        }
        Toast.makeText(this, "已删除 $deleted 个文件", Toast.LENGTH_SHORT).show()
        selectedFiles.clear()
        loadFiles()
    }

    private fun updateButtonState() {
        btnDelete.isEnabled = selectedFiles.isNotEmpty()
        btnDelete.text = if (selectedFiles.isEmpty()) "删除" else "删除 (${selectedFiles.size})"
        btnSelectAll.text = if (selectedFiles.size == files.size && files.isNotEmpty()) "取消全选" else "全选"
    }
}

class DownloadFileAdapter(
    private val files: List<File>,
    private val selectedFiles: MutableSet<File>,
    private val onSelectionChanged: () -> Unit,
    private val onCopy: (File) -> Unit
) : RecyclerView.Adapter<DownloadFileAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
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
        val file = files[position]
        holder.tvName.text = file.name
        holder.tvInfo.text = "${DownloadHelper.formatFileSize(file.length())}  ${dateFormat.format(Date(file.lastModified()))}"

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selectedFiles.contains(file)

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedFiles.add(file) else selectedFiles.remove(file)
            onSelectionChanged()
        }

        holder.btnCopy.setOnClickListener { onCopy(file) }
        holder.itemView.setOnClickListener {
            holder.checkBox.isChecked = !holder.checkBox.isChecked
        }
    }

    override fun getItemCount() = files.size
}
