package com.example.aggregator

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileListAdapter(
    private var files: List<File>,
    private val onFolderClick: (File) -> Unit
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
        val file = files[position]
        holder.fileName.text = file.name

        if (file.isDirectory) {
            holder.fileIcon.setImageResource(android.R.drawable.ic_menu_view) // Folder icon
            val itemCount = file.listFiles()?.size ?: 0
            holder.fileDetails.text = "$itemCount items"

            // Navigate into folder on click
            holder.itemView.setOnClickListener {
                onFolderClick(file)
            }

        } else {
            holder.fileIcon.setImageResource(android.R.drawable.ic_menu_gallery) // File icon
            val sizeKB = file.length() / 1024
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val modifiedDate = dateFormat.format(Date(file.lastModified()))
            holder.fileDetails.text = "$sizeKB KB • $modifiedDate"

            // Open file on click
            holder.itemView.setOnClickListener {
                // Check if it's a text file
                if (file.name.endsWith(".txt", ignoreCase = true)) {
                    // Open in built-in viewer
                    openTextFileInViewer(holder.itemView.context, file)
                } else {
                    // Open with system chooser
                    openFile(holder.itemView.context, file)
                }
            }
        }
    }

    override fun getItemCount() = files.size

    fun updateFiles(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    /**
     * Open text files (.txt) in built-in text viewer
     */
    private fun openTextFileInViewer(context: android.content.Context, file: File) {
        try {
            val intent = Intent(context, TextViewerActivity::class.java).apply {
                putExtra("file_path", file.absolutePath)
                putExtra("file_name", file.name)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Cannot open file: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Open other file types with system chooser
     */
    private fun openFile(context: android.content.Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Cannot open file: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
    }
}