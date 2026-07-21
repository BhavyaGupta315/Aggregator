package com.example.aggregator

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TextViewerActivity : AppCompatActivity() {
    private lateinit var fileNameText: TextView
    private lateinit var fileContentText: TextView
    private lateinit var backButton: Button
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_viewer)

        fileNameText = findViewById(R.id.fileNameText)
        fileContentText = findViewById(R.id.fileContentText)
        backButton = findViewById(R.id.backButton)
        scrollView = findViewById(R.id.scrollView)

        // Get file path from intent
        val reportId = intent.getLongExtra("report_id", -1L)
        val fileName = intent.getStringExtra("file_name") ?: "Unknown"
        Log.d("TextViewerActivity", "Opening report: $reportId")

        // Set title
        fileNameText.text = "📄 $fileName"

        // Load and display file content
        displayFile(reportId)

        // Back button
        backButton.setOnClickListener {
            finish()
        }
    }

    private fun displayFile(reportId: Long) {
        try {
            if (reportId < 0) {
                fileContentText.text = "❌ No report selected"
                return
            }

            val report = AggregatorDatabase.getInstance(this).patientReportDao().getReportById(reportId)
            if (report == null) {
                fileContentText.text = "❌ Report not found"
                Log.e("TextViewerActivity", "Report not found: $reportId")
                return
            }
            fileContentText.text = report.content
            Log.d("TextViewerActivity", "✅ Report loaded: ${report.content.length} chars")

            // Scroll to top
            scrollView.post {
                scrollView.scrollTo(0, 0)
            }

        } catch (e: Exception) {
            Log.e("TextViewerActivity", "❌ Error reading file: ${e.message}", e)
            fileContentText.text = "❌ Error reading file:\n${e.message}"
        }
    }
}
