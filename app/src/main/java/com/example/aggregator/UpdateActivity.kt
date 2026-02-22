package com.example.aggregator

import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fileNameTextView: TextView
    private lateinit var tapPromptTextView: TextView
    private val APP_DIRECTORY = "NursingDevice"
    private val MASTER_FILE_NAME = "aggregated_reports.txt"
    private val PERMISSION_REQUEST_CODE = 100
    private var sessionKey: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        tapPromptTextView = findViewById(R.id.tapPromptText)
        statusTextView = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.transferProgressBar)
        fileNameTextView = findViewById(R.id.fileNameText)

        Log.d("UpdateActivity", "⚠️ Skipping permission check - testing mode")
        enableNFC()
    }

    private fun enableNFC() {
        tapPromptTextView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        fileNameTextView.visibility = View.GONE
        statusTextView.text = "📱 Waiting for NFC device..."
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }
    override fun onTagDiscovered(tag: Tag?) {
        Log.d("UpdateActivity", "NFC Tag Discovered!")
        runOnUiThread {
            tapPromptTextView.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            fileNameTextView.visibility = View.VISIBLE
            statusTextView.text = "Authenticating..."
        }

        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 7000 // Extended timeout for RSA math

            // 1. Select AID
            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess()) throw IOException("AID selection failed.")

            // --- MUTUAL AUTHENTICATION START ---

            // A. Generate Session Key
            sessionKey = CryptoUtils.generateSessionKey()

            // B. Encrypt Session Key with Sender's Public Key
            val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey!!, CryptoUtils.getOtherPublicKey())

            // C. Sign the Encrypted Key with My Private Key
            val signature = CryptoUtils.rsaSign(encryptedKey, CryptoUtils.getMyPrivateKey())

            // D. Send Step 1: Encrypted Key
            val cmd1 = Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey)
            response = isoDep.transceive(cmd1)
            if (!response.isSuccess()) throw IOException("Auth Handshake Step 1 Failed")

            // E. Send Step 2: Signature
            val cmd2 = Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_SIG, signature)
            response = isoDep.transceive(cmd2)

            // F. Verify ACK (Decrypt "AUTH_OK")
            if (!response.isSuccess()) throw IOException("Auth Handshake Step 2 Failed")

            val encryptedAck = response.getData()
            val ackBytes = CryptoUtils.xorEncryptDecrypt(encryptedAck, sessionKey!!)
            val ackString = String(ackBytes, Charsets.UTF_8)

            if (ackString != "AUTH_OK") throw IOException("Auth Failed: Invalid ACK")

            Log.d("UpdateActivity", "Mutual Auth Success. Secure Channel Established.")
            runOnUiThread { statusTextView.text = "Authenticated! Fetching data..." }

            // --- SECURE DATA TRANSFER START ---

            // 2. Get Metadata (Encrypted)
            response = transceiveSecure(isoDep, Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw IOException("Failed to get metadata.")

            val metadataPayload = response.getData() // This is now DECRYPTED
            val transferMode = String(metadataPayload.copyOfRange(0, 1), Charsets.UTF_8)

            when (transferMode) {
                "T" -> handleTextReception(metadataPayload.copyOfRange(1, metadataPayload.size))
                "F" -> handleFileReceptionSecure(isoDep, metadataPayload.copyOfRange(1, metadataPayload.size))
                "M" -> handleMultiFileReceptionSecure(isoDep)
                else -> throw IOException("Unknown transfer mode: $transferMode")
            }

        } catch (e: Exception) {
            Log.e("UpdateActivity", "Error: ${e.message}", e)
            runOnUiThread {
                statusTextView.text = "Error: ${e.message}"
                Toast.makeText(this, "Connection Failed", Toast.LENGTH_SHORT).show()
                resetUI()
            }
        } finally {
            try { isoDep.close() } catch (e: IOException) { }
        }
    }
    private fun transceiveSecure(isoDep: IsoDep, command: ByteArray): ByteArray {
        val response = isoDep.transceive(command)
        if (!response.isSuccess()) return response // Return error codes as-is

        // Decrypt successful payload
        val encryptedData = response.getData()
        val decryptedData = CryptoUtils.xorEncryptDecrypt(encryptedData, sessionKey!!)

        // Re-attach success status word (90 00) so helpers know it worked
        return Utils.concatArrays(decryptedData, Utils.SELECT_OK_SW)
    }
    private fun handleFileReceptionSecure(isoDep: IsoDep, fileInfoPayload: ByteArray) {
        val fileSize = ByteBuffer.wrap(fileInfoPayload.copyOfRange(0, 4)).int
        val fileName = String(fileInfoPayload.copyOfRange(4, fileInfoPayload.size), Charsets.UTF_8)

        runOnUiThread { fileNameTextView.text = "Receiving: $fileName" }
        val fileStream = ByteArrayOutputStream(fileSize)

        while (fileStream.size() < fileSize) {
            // Use transceiveSecure instead of raw transceive
            val response = transceiveSecure(isoDep, Utils.GET_NEXT_DATA_CHUNK_COMMAND)
            if (!response.isSuccess()) break

            fileStream.write(response.getData())

            val progress = (fileStream.size() * 100 / fileSize)
            runOnUiThread {
                progressBar.progress = progress
                statusTextView.text = "${fileStream.size()} / $fileSize bytes"
            }
        }

        val finalPayload = fileStream.toByteArray()
        val fileContent = String(finalPayload, Charsets.UTF_8)
        appendToMasterFile(fileContent, fileName)
        runOnUiThread { Toast.makeText(this, "✅ $fileName Saved!", Toast.LENGTH_LONG).show() }
    }

    // --- Modified Multi-File Handler (Copy this into your class) ---
    private fun handleMultiFileReceptionSecure(isoDep: IsoDep) {
        val response = transceiveSecure(isoDep, Utils.GET_FILE_INFO_COMMAND)
        if (!response.isSuccess()) throw IOException("Failed to get file metadata.")

        val metadataPayload = response.getData()
        val fileSize = ByteBuffer.wrap(metadataPayload.copyOfRange(0, 4)).int
        val fileName = String(metadataPayload.copyOfRange(4, metadataPayload.size), Charsets.UTF_8)

        Log.d("UpdateActivity", "Receiving: $fileName ($fileSize bytes)")

        val fileStream = ByteArrayOutputStream(fileSize)

        while (fileStream.size() < fileSize) {
            val chunkResponse = transceiveSecure(isoDep, Utils.GET_NEXT_DATA_CHUNK_COMMAND)
            if (!chunkResponse.isSuccess()) break
            fileStream.write(chunkResponse.getData())

            runOnUiThread {
                progressBar.progress = (fileStream.size() * 100 / fileSize)
            }
        }

        val fileContent = String(fileStream.toByteArray(), Charsets.UTF_8)
        saveAppendedFile(fileContent, fileName)
        runOnUiThread { Toast.makeText(this, "Received!", Toast.LENGTH_SHORT).show() }
    }

    private fun appendToMasterFile(fileContent: String, fileName: String) {
        try {
            val appFilesDir = getExternalFilesDir(null) ?: throw IOException("Storage not available")
            val nursingDir = File(appFilesDir, APP_DIRECTORY)
            if (!nursingDir.exists()) nursingDir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayFolder = dateFormat.format(Date())
            val todayDir = File(nursingDir, todayFolder)
            if (!todayDir.exists()) todayDir.mkdirs()

            val masterFile = File(todayDir, MASTER_FILE_NAME)

            // Create a separator header for this file entry
            val separator = "\n\n" + "=".repeat(80) + "\n"
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val header = "FILE: $fileName | RECEIVED: $timestamp\n" + "=".repeat(80) + "\n\n"

            // Append: header + content + separator
            val appendData = header + fileContent + separator

            if (masterFile.exists()) {
                masterFile.appendText(appendData)
            } else {
                masterFile.writeText(appendData)
            }

            Log.d("UpdateActivity", "✅ Content appended to: $${masterFile.absolutePath}")
            Log.d("UpdateActivity", "Master file size: $${masterFile.length()} bytes")

            runOnUiThread {
                Toast.makeText(
                    this,
                    "✅ Saved to: $todayFolder/$MASTER_FILE_NAME",
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (e: Exception) {
            Log.e("UpdateActivity", "❌ Error appending: $${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "❌ Error: $${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAppendedFile(fileContent: String, fileName: String) {
        try {
            val appFilesDir = getExternalFilesDir(null) ?: throw IOException("Storage not available")
            val nursingDir = File(appFilesDir, APP_DIRECTORY)
            if (!nursingDir.exists()) nursingDir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayFolder = dateFormat.format(Date())
            val todayDir = File(nursingDir, todayFolder)
            if (!todayDir.exists()) todayDir.mkdirs()

            val masterFile = File(todayDir, MASTER_FILE_NAME)

            // Create separator header
            val separator = "\n\n" + "=".repeat(80) + "\n"
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val header = "FILE: $fileName | RECEIVED: $timestamp\n" + "=".repeat(80) + "\n\n"

            val appendData = header + fileContent + separator

            if (masterFile.exists()) {
                masterFile.appendText(appendData)
            } else {
                masterFile.writeText(appendData)
            }

            Log.d("UpdateActivity", "✅ Appended file saved: $${masterFile.absolutePath}")
            Log.d("UpdateActivity", "Master file size: $${masterFile.length()} bytes")

        } catch (e: Exception) {
            Log.e("UpdateActivity", "❌ Error saving: $${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "❌ Error: $${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val receivedString = String(payload, Charsets.UTF_8)
        Log.d("UpdateActivity", "Received text: $receivedString")
        runOnUiThread {
            statusTextView.text = "✅ Text received!"
            fileNameTextView.text = receivedString
        }
    }

    private fun resetUI() {
        tapPromptTextView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        fileNameTextView.visibility = View.GONE
        statusTextView.text = "📱 Waiting for NFC device..."
        progressBar.progress = 0
    }

    private fun ByteArray.isSuccess(): Boolean = this.size >= 2 && this.takeLast(2) == Utils.SELECT_OK_SW.toList()
    private fun ByteArray.getData(): ByteArray = this.copyOfRange(0, this.size - 2)

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
                this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
        }
    }

}