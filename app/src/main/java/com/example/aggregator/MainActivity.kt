package com.example.aggregator

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var fileRecyclerView: RecyclerView
    private lateinit var currentPathText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var fileListAdapter: MainFileListAdapter
    private lateinit var reportDao: PatientReportDao
    private var selectedDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The DB is encrypted with an in-memory PIN key. If the process was killed
        // and Android restored us straight into MainActivity, the key is gone —
        // send the user back to login rather than crashing on a locked DB.
        if (!AggregatorSession.isUnlocked) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        scheduleCloudSync()
        reportDao = AggregatorDatabase.getInstance(this).patientReportDao()

        val patientName = intent.getStringExtra("patient_name") ?: "Patient"
        val directoryHeader = findViewById<TextView>(R.id.directoryHeader)
        directoryHeader.text = "📁 Files for $patientName:"

        val sharePatientBtn = findViewById<Button>(R.id.sharePatientBtn)
        val syncButton = findViewById<Button>(R.id.SenderButton)
        val syncAllButton = findViewById<Button>(R.id.SyncAllNotesButton)
        val updateButton = findViewById<Button>(R.id.ReceiverButton)
        val transportSwitch = findViewById<Switch>(R.id.transportModeSwitch)
        transportSwitch.isChecked = TransferModeStore.isWifiDirect(this)
        transportSwitch.setOnCheckedChangeListener { _, checked ->
            TransferModeStore.setWifiDirect(this, checked)
        }

        // Cloud sync button — opens dedicated sync screen
        findViewById<Button>(R.id.SyncHealthDbButton)?.setOnClickListener {
            startActivity(Intent(this, SyncCloudActivity::class.java))
        }

        sharePatientBtn.setOnClickListener {
            startActivity(Intent(this, PatientShareActivity::class.java).apply {
                putExtra("patient_name", patientName)
            })
        }

        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        currentPathText = findViewById(R.id.currentPathText)
        backButton = findViewById(R.id.backButton)
        initializeBrowser()

        updateButton.setOnClickListener {
            if (TransferModeStore.isWifiDirect(this)) {
                startActivity(Intent(this, WifiDirectTransferActivity::class.java).apply {
                    putExtra(WifiDirectTransferActivity.EXTRA_DIRECTION, WifiDirectTransferActivity.DIRECTION_RECEIVE)
                })
            } else {
                startActivity(Intent(this, UpdateActivity::class.java))
            }
        }

        syncButton.setOnClickListener {
            val pName = intent.getStringExtra("patient_name") ?: "Unknown"
            startActivity(Intent(this, SyncActivity::class.java).apply {
                putExtra("patient_name", pName)
                putExtra(SyncActivity.EXTRA_SYNC_MODE, SyncActivity.MODE_TODAY)
            })
        }

        syncAllButton.setOnClickListener {
            val pName = intent.getStringExtra("patient_name") ?: "Unknown"
            startActivity(Intent(this, SyncActivity::class.java).apply {
                putExtra("patient_name", pName)
                putExtra(SyncActivity.EXTRA_SYNC_MODE, SyncActivity.MODE_ALL)
            })
        }

        backButton.setOnClickListener {
            navigateBack()
        }

    }

    override fun onResume() {
        super.onResume()
        if (!AggregatorSession.isUnlocked) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        if (::fileListAdapter.isInitialized) loadCurrentDirectory()
    }

    private fun initializeBrowser() {
        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileListAdapter = MainFileListAdapter(emptyList()) { folder ->
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
                MainBrowserItem.DateFolder(
                    date = date,
                    count = reportDao.getReportsForDay(currentPatient.id, date).size
                )
            }
            fileListAdapter.updateFiles(items)
        } else {
            currentPathText.text = "/$selectedDate"
            backButton.visibility = View.VISIBLE
            val items = reportDao.getReportsForDay(currentPatient.id, selectedDate!!).map { report ->
                MainBrowserItem.ReportFile(
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

    private fun scheduleCloudSync() {
        runCatching {
            val schedulerClass = Class.forName("$packageName.SyncWorkScheduler")
            val scheduler = schedulerClass.getField("INSTANCE").get(null)
            schedulerClass.getMethod("schedulePeriodicSync", android.content.Context::class.java)
                .invoke(scheduler, this)
            schedulerClass.getMethod("enqueueImmediateSync", android.content.Context::class.java)
                .invoke(scheduler, this)
        }
    }
}

private sealed class MainBrowserItem {
    data class DateFolder(val date: String, val count: Int) : MainBrowserItem()
    data class ReportFile(
        val reportId: Long,
        val fileName: String,
        val content: String,
        val updatedAt: Long
    ) : MainBrowserItem()
}

private class MainFileListAdapter(
    private var items: List<MainBrowserItem>,
    private val onFolderClick: (MainBrowserItem.DateFolder) -> Unit
) : RecyclerView.Adapter<MainFileListAdapter.FileViewHolder>() {

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
            is MainBrowserItem.DateFolder -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_view)
                holder.fileName.text = item.date
                holder.fileDetails.text = "${item.count} item(s)"
                holder.itemView.setOnClickListener { onFolderClick(item) }
            }

            is MainBrowserItem.ReportFile -> {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                holder.fileName.text = item.fileName
                val sizeKB = item.content.toByteArray().size / 1024
                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val modifiedDate = dateFormat.format(Date(item.updatedAt))
                holder.fileDetails.text = "$sizeKB KB - $modifiedDate"
                holder.itemView.setOnClickListener {
                    val context = holder.itemView.context
                    val intent = Intent().setClassName(context, "${context.packageName}.TextViewerActivity")
                    intent.putExtra("report_id", item.reportId)
                    intent.putExtra("file_name", item.fileName)
                    context.startActivity(intent)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateFiles(newItems: List<MainBrowserItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
