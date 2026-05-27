package org.tan.cdntest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ActiveDownloadAdapter(
    private var items: List<DownloadTask> = emptyList(),
    private val onPauseResume: (DownloadTask) -> Unit,
    private val onCancel: (DownloadTask) -> Unit
) : RecyclerView.Adapter<ActiveDownloadAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val tvPercent: TextView = view.findViewById(R.id.tvPercent)
        val tvProgress: TextView = view.findViewById(R.id.tvProgress)
        val tvSpeed: TextView = view.findViewById(R.id.tvSpeed)
        val btnPause: ImageButton = view.findViewById(R.id.btnPause)
        val btnCancel: ImageButton = view.findViewById(R.id.btnCancel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_active_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = items[position]
        val isM3u8 = DownloadEngine.isM3u8Task(task.url)

        holder.tvName.text = task.fileName

        if (isM3u8) {
            bindM3u8Task(holder, task)
        } else {
            bindNormalTask(holder, task)
        }

        holder.btnPause.setOnClickListener { onPauseResume(task) }
        holder.btnCancel.setOnClickListener { onCancel(task) }
    }

    private fun bindM3u8Task(holder: ViewHolder, task: DownloadTask) {
        val total = task.totalBytes.toInt()
        val downloaded = task.downloadedBytes.toInt()

        if (task.fileName.contains("(合并中...)")) {
            // Merge phase
            holder.progressBar.isIndeterminate = true
            holder.tvPercent.text = ""
            holder.tvProgress.text = "分片 $downloaded/$total · 合并转码中..."
            holder.tvSpeed.text = ""
            holder.tvSpeed.visibility = View.VISIBLE
            holder.btnPause.isEnabled = false
        } else {
            // Download phase
            holder.progressBar.isIndeterminate = false
            val percent = if (total > 0) (downloaded * 100 / total) else 0
            holder.progressBar.max = total
            holder.progressBar.progress = downloaded
            holder.tvPercent.text = "${percent}%"
            holder.tvProgress.text = "分片 $downloaded/$total"
            holder.tvSpeed.text = ""
            holder.tvSpeed.visibility = View.GONE
            holder.btnPause.isEnabled = true
        }

        when (task.status) {
            DownloadStatus.PAUSED -> {
                holder.tvSpeed.text = "已暂停"
                holder.tvSpeed.visibility = View.VISIBLE
                holder.btnPause.setImageResource(R.drawable.ic_play)
                holder.btnPause.isEnabled = true
            }
            DownloadStatus.PENDING -> {
                holder.tvSpeed.text = "等待中"
                holder.tvSpeed.visibility = View.VISIBLE
                holder.btnPause.isEnabled = false
            }
            else -> {}
        }
    }

    private fun bindNormalTask(holder: ViewHolder, task: DownloadTask) {
        holder.progressBar.isIndeterminate = false
        val percent = if (task.totalBytes > 0) (task.downloadedBytes * 100 / task.totalBytes).toInt() else 0
        holder.progressBar.progress = percent
        holder.tvPercent.text = "${percent}%"

        val downloaded = DownloadHelper.formatFileSize(task.downloadedBytes)
        val total = if (task.totalBytes > 0) DownloadHelper.formatFileSize(task.totalBytes) else "未知"
        holder.tvProgress.text = "$downloaded / $total"

        when (task.status) {
            DownloadStatus.RUNNING -> {
                holder.tvSpeed.text = DownloadHelper.formatFileSize(task.speed) + "/s"
                holder.tvSpeed.visibility = View.VISIBLE
                holder.btnPause.setImageResource(R.drawable.ic_pause)
                holder.btnPause.isEnabled = true
            }
            DownloadStatus.PAUSED -> {
                holder.tvSpeed.text = "已暂停"
                holder.tvSpeed.visibility = View.VISIBLE
                holder.btnPause.setImageResource(R.drawable.ic_play)
                holder.btnPause.isEnabled = true
            }
            DownloadStatus.PENDING -> {
                holder.tvSpeed.text = "等待中"
                holder.tvSpeed.visibility = View.VISIBLE
                holder.btnPause.isEnabled = false
            }
            else -> {
                holder.tvSpeed.visibility = View.GONE
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<DownloadTask>) {
        items = newItems
        notifyDataSetChanged()
    }
}
