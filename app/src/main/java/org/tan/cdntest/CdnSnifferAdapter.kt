package org.tan.cdntest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SnifferResult(
    val url: String,
    val type: String // image, video, audio, download, other
)

class CdnSnifferAdapter(
    private var items: List<SnifferResult> = emptyList(),
    private val onCopy: (String) -> Unit,
    private val onDownload: (String) -> Unit
) : RecyclerView.Adapter<CdnSnifferAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvUrl: TextView = view.findViewById(R.id.tvUrl)
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
        holder.btnDownload.setOnClickListener { onDownload(item.url) }
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
