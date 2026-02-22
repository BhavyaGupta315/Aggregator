package com.example.aggregator

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncActivity  : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private val APP_DIRECTORY = "NursingDevice"
    private val MASTER_FILE_NAME = "aggregated_reports.txt"  // Changed to .txt
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)
        progressBar = findViewById(R.id.syncProgressBar)
        statusText = findViewById(R.id.syncStatusText)
        fileNameText = findViewById(R.id.syncFileNameText)

        Log.d("SyncActivity", "⚠️ Skipping permission check - testing mode")
        prepareFileForSync()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("SyncActivity", "✅ Permission granted")
                    prepareFileForSync()
                } else {
                    statusText.text = "❌ Storage permission denied"
                    fileNameText.text = "Cannot access files"
                    Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun prepareFileForSync() {
        try {
            val appFilesDir = getExternalFilesDir(null) ?: throw Exception("Storage not available")
            val appDir = File(appFilesDir, APP_DIRECTORY)
            if (!appDir.exists()) {
                statusText.text = "❌ No directory"
                fileNameText.text = "NursingDevice folder not found"
                return
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayFolder = dateFormat.format(Date())
            val todayDir = File(appDir, todayFolder)

            if (!todayDir.exists()) {
                statusText.text = "📭 No folder for today"
                fileNameText.text = "Folder: $todayFolder (not created)"
                Toast.makeText(this, "No data folder for today", Toast.LENGTH_SHORT).show()
                return
            }

            // Look for master file
            val masterFile = File(todayDir, MASTER_FILE_NAME)

            if (!masterFile.exists()) {
                statusText.text = "📭 No aggregated data"
                fileNameText.text = "Master file not found in $todayFolder"
                Toast.makeText(this, "No appended data to sync", Toast.LENGTH_SHORT).show()
                return
            }

            val fileSize = masterFile.length()
            Log.d("SyncActivity", "Found master file: $${masterFile.name}, Size: $fileSize bytes")

            // Read preview of file
            val filePreview = masterFile.readText(Charsets.UTF_8).take(500)

            statusText.text = "✅ Ready to sync all aggregated reports"
            fileNameText.text = "📁 File: $MASTER_FILE_NAME\nSize: $fileSize bytes\nLocation: $todayFolder\n\nPreview:\n$filePreview..."

            // Read the single master file and prepare for transfer
            val fileData = masterFile.readBytes()

            // Create a list with single FileData entry
            val fileDataList = listOf(
                FileData(MASTER_FILE_NAME, fileData)
            )

            Log.d("SyncActivity", "Preparing master file for sync: $fileSize bytes")
            MyHostApduService.setMultipleFilesForTransfer(fileDataList)

            Toast.makeText(
                this,
                "✅ Ready to sync ($fileSize bytes) - Tap aggregator",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Log.e("SyncActivity", "❌ Error: $${e.message}", e)
            statusText.text = "❌ Error"
            fileNameText.text = e.message ?: "Unknown error"
            Toast.makeText(this, "Error: $${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}