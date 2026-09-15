package com.xong.driveupload

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val onOpenLocation: (DriveFileItem) -> Unit,
    private val onChangeSharing: (DriveFileItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {
    private val items = mutableListOf<DriveFileItem>()

    fun submit(newItems: List<DriveFileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        val size = item.sizeBytes?.let(Utils::formatBytes).orEmpty()
        val date = Utils.formatDriveTime(item.createdTime)
        holder.meta.text = listOf(size, date).filter { it.isNotBlank() }.joinToString(" · ")
        holder.itemView.setOnClickListener { onOpenLocation(item) }
        holder.share.setOnClickListener { onChangeSharing(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
        val share: ImageButton = view.findViewById(R.id.shareButton)
    }
}
