package com.example.aggregator

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
class MainActivity : AppCompatActivity() {

    private lateinit var fileRecyclerView: RecyclerView
    private lateinit var currentPathText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var fileListAdapter: FileListAdapter
    private lateinit var reportDao: PatientReportDao
    private var selectedDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SyncWorkScheduler.schedulePeriodicSync(this)
        SyncWorkScheduler.enqueueImmediateSync(this)
        reportDao = AggregatorDatabase.getInstance(this).patientReportDao()

        val patientName = intent.getStringExtra("patient_name") ?: "Patient"
        val directoryHeader = findViewById<TextView>(R.id.directoryHeader)
        directoryHeader.text = "📁 Files for $patientName:"

        val sharePatientBtn = findViewById<Button>(R.id.sharePatientBtn)
        val syncButton = findViewById<Button>(R.id.SenderButton)
        val updateButton = findViewById<Button>(R.id.ReceiverButton)

        // Cloud sync button — opens dedicated sync screen
        findViewById<Button>(R.id.SyncHealthDbButton)?.setOnClickListener {
            startActivity(Intent(this, SyncCloudActivity::class.java))
        }

        sharePatientBtn.setOnClickListener {
            val patientName = intent.getStringExtra("patient_name") ?: return@setOnClickListener
            startActivity(Intent(this, PatientShareActivity::class.java).apply {
                putExtra("patient_name", patientName)
            })
        }

        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        currentPathText = findViewById(R.id.currentPathText)
        backButton = findViewById(R.id.backButton)
        initializeBrowser()

        updateButton.setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }

        syncButton.setOnClickListener {
            val pName = intent.getStringExtra("patient_name") ?: "Unknown"
            startActivity(Intent(this, SyncActivity::class.java).apply {
                putExtra("patient_name", pName)
            })
        }

        backButton.setOnClickListener {
            navigateBack()
        }

    }

    override fun onResume() {
        super.onResume()
        if (::fileListAdapter.isInitialized) loadCurrentDirectory()
    }

    private fun initializeBrowser() {
        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileListAdapter = FileListAdapter(emptyList()) { folder ->
            selectedDate = folder.date
            loadCurrentDirectory()
        }
        fileRecyclerView.adapter = fileListAdapter

        loadCurrentDirectory()
    }

    private fun navigateBack() {
        if (selectedDate != null) {
            selectedDate = null
            loadCurrentDirectory()
        }
    }

    private fun loadCurrentDirectory() {
        val currentPatient = PatientManager(this).getCurrentPatient()
        if (currentPatient == null) {
            currentPathText.text = "/"
            backButton.visibility = View.GONE
            fileListAdapter.updateFiles(emptyList())
            return
        }

        if (selectedDate == null) {
            currentPathText.text = "/"
            backButton.visibility = View.GONE
            val items = reportDao.getAvailableDates(currentPatient.id).map { date ->
                BrowserItem.DateFolder(
                    date = date,
                    count = reportDao.getReportsForDay(currentPatient.id, date).size
                )
            }
            fileListAdapter.updateFiles(items)
        } else {
            currentPathText.text = "/$selectedDate"
            backButton.visibility = View.VISIBLE
            val items = reportDao.getReportsForDay(currentPatient.id, selectedDate!!).map { report ->
                BrowserItem.ReportFile(
                    reportId = report.id,
                    fileName = "${report.patientName.replace(" ", "_")}_${report.reportDate}.txt",
                    content = report.content,
                    updatedAt = report.updatedAt
                )
            }
            fileListAdapter.updateFiles(items)
        }
        fileRecyclerView.scrollToPosition(0)
    }
}
