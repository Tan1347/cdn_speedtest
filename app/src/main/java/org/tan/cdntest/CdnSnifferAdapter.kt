package org.tan.cdntest

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SnifferResult(
    val url: String,
    val type: String, // image, video, audio, download, other
    val duration: Long = 0,
    val estimatedSize: Long = 0,
    val thumbnail: Bitmap? = null,
    val isM3u8: Boolean = false,
    val segmentCount: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

class CdnSnifferAdapter(
    private var items: List<SnifferResult> = emptyList(),
    private val onCopy: (String) -> Unit,
    private val onDownload: (SnifferResult) -> Unit,
    private val onPreview: (SnifferResult) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<CdnSnifferAdapter.ViewHolder>() {

    var multiSelectMode = false
        private set
    val selectedItems = mutableSetOf<Int>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvUrl: TextView = view.findViewById(R.id.tvUrl)
        val tvVideoInfo: TextView = view.findViewById(R.id.tvVideoInfo)
        val btnDownload: ImageButton = view.findViewById(R.id.btnDownload)
        val btnCopy: ImageButton = view.findViewById(R.id.btnCopy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sniffer_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvType.text = getTypeLabel(item.type)
        holder.tvUrl.text = item.url

        // Multi-select checkbox
        if (multiSelectMode) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked = selectedItems.contains(position)
            holder.cbSelect.setOnClickListener {
                toggleSelection(position)
            }
        } else {
            holder.cbSelect.visibility = View.GONE
        }

        if (item.type == "video" || (item.isM3u8 && item.thumbnail != null)) {
            holder.ivThumb.visibility = View.VISIBLE
            if (item.thumbnail != null) {
                holder.ivThumb.setImageBitmap(item.thumbnail)
            } else {
                holder.ivThumb.setImageResource(android.R.color.darker_gray)
            }
        } else {
            holder.ivThumb.visibility = View.GONE
        }

        // Show info for all resource types when available
        if (item.duration > 0 || item.estimatedSize > 0 || item.width > 0 || item.segmentCount > 0) {
            holder.tvVideoInfo.visibility = View.VISIBLE
            val parts = mutableListOf<String>()
            if (item.width > 0 && item.height > 0) parts.add("${item.width}x${item.height}")
            if (item.duration > 0) parts.add(VideoInfoFetcher.formatDuration(item.duration))
            if (item.isM3u8 && item.segmentCount > 0) {
                parts.add("${item.segmentCount} 个分片")
            } else if (item.estimatedSize > 0) {
                parts.add(DownloadHelper.formatFileSize(item.estimatedSize))
            }
            holder.tvVideoInfo.text = parts.joinToString(" · ")
        } else if (item.type == "video" || item.isM3u8) {
            holder.tvVideoInfo.visibility = View.VISIBLE
            holder.tvVideoInfo.text = "分析中..."
        } else {
            holder.tvVideoInfo.visibility = View.GONE
        }

        holder.btnDownload.setOnClickListener { onDownload(item) }
        holder.btnCopy.setOnClickListener { onCopy(item.url) }

        if (multiSelectMode) {
            holder.itemView.setOnClickListener { toggleSelection(position) }
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.itemView.setOnLongClickListener {
                enterMultiSelect(position)
                true
            }
            if (item.type == "video" || item.type == "audio") {
                holder.itemView.setOnClickListener { onPreview(item) }
            } else {
                holder.itemView.setOnClickListener(null)
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<SnifferResult>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun enterMultiSelect(position: Int) {
        multiSelectMode = true
        selectedItems.add(position)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun selectAll() {
        selectedItems.clear()
        for (i in items.indices) selectedItems.add(i)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun getSelectedResults(): List<SnifferResult> {
        return selectedItems.map { items[it] }
    }

    private fun toggleSelection(position: Int) {
        if (selectedItems.contains(position)) {
            selectedItems.remove(position)
        } else {
            selectedItems.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedItems.size)
        if (selectedItems.isEmpty()) {
            exitMultiSelect()
        }
    }

    private fun getTypeLabel(type: String): String = when (type) {
        "image" -> "[图片]"
        "video" -> "[视频]"
        "audio" -> "[音乐]"
        "download" -> "[下载]"
        else -> "[其他]"
    }
}
