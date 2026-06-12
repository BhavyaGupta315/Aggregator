package com.example.aggregator

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class BrowserItem {
    data class DateFolder(val date: String, val count: Int) : BrowserItem()
    data class ReportFile(val reportId: Long, val fileName: String, val content: String, val updatedAt: Long) : BrowserItem()
}

class FileListAdapter(
    private var items: List<BrowserItem>,
    private val onFolderClick: (BrowserItem.DateFolder) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileNameText)
        val fileDetails: TextView = view.findViewById(R.id.fileDetailsText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        when (val item = items[position]) {
            is BrowserItem.DateFolder -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_view)
                holder.fileName.text = item.date
                holder.fileDetails.text = "${item.count} item(s)"
                holder.itemView.setOnClickListener { onFolderClick(item) }
            }

            is BrowserItem.ReportFile -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                holder.fileName.text = item.fileName
                val sizeKB = item.content.toByteArray().size / 1024
                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val modifiedDate = dateFormat.format(Date(item.updatedAt))
                holder.fileDetails.text = "$sizeKB KB • $modifiedDate"
                holder.itemView.setOnClickListener {
                    val intent = Intent(holder.itemView.context, TextViewerActivity::class.java).apply {
                        putExtra("report_id", item.reportId)
                        putExtra("file_name", item.fileName)
                    }
                    holder.itemView.context.startActivity(intent)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateFiles(newItems: List<BrowserItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
