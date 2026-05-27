package org.tan.cdntest

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    val isM3u8: Boolean = false
)

class CdnSnifferAdapter(
    private var items: List<SnifferResult> = emptyList(),
    private val onCopy: (String) -> Unit,
    private val onDownload: (SnifferResult) -> Unit
) : RecyclerView.Adapter<CdnSnifferAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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

        if (item.type == "video") {
            holder.ivThumb.visibility = View.VISIBLE
            if (item.thumbnail != null) {
                holder.ivThumb.setImageBitmap(item.thumbnail)
            } else {
                holder.ivThumb.setImageResource(android.R.color.darker_gray)
            }

            if (item.duration > 0 || item.estimatedSize > 0) {
                holder.tvVideoInfo.visibility = View.VISIBLE
                val parts = mutableListOf<String>()
                if (item.duration > 0) parts.add(VideoInfoFetcher.formatDuration(item.duration))
                if (item.estimatedSize > 0) parts.add(DownloadHelper.formatFileSize(item.estimatedSize))
                if (item.isM3u8) parts.add("HLS")
                holder.tvVideoInfo.text = parts.joinToString(" · ")
            } else {
                holder.tvVideoInfo.visibility = View.VISIBLE
                holder.tvVideoInfo.text = "分析中..."
            }
        } else {
            holder.ivThumb.visibility = View.GONE
            holder.tvVideoInfo.visibility = View.GONE
        }

        holder.btnDownload.setOnClickListener { onDownload(item) }
        holder.btnCopy.setOnClickListener { onCopy(item.url) }
        holder.itemView.setOnLongClickListener {
            onCopy(item.url)
            true
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<SnifferResult>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun getTypeLabel(type: String): String = when (type) {
        "image" -> "[图片]"
        "video" -> "[视频]"
        "audio" -> "[音乐]"
        "download" -> "[下载]"
        else -> "[其他]"
    }
}
