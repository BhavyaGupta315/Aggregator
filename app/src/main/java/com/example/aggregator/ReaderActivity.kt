package com.example.aggregator

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class ReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var statusTextView: TextView? = null
    private var receivedDataTextView: TextView? = null
    private var scrollView: ScrollView? = null
    private val CHUNK_SIZE = 245
    private val MAX_CHUNK_COUNT = 50000

    // Store the session key locally for this transfer
    private var sessionKey: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        try {
            statusTextView = findViewById(R.id.statusText)
            receivedDataTextView = findViewById(R.id.receivedDataText)
            scrollView = findViewById(R.id.scrollView)

            if (statusTextView == null || receivedDataTextView == null) {
                Toast.makeText(this, "Layout error", Toast.LENGTH_SHORT).show()
                return
            }

            statusTextView?.text = "📱 Waiting for NFC device..."
            enableNFC()

        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error initializing views: ${e.message}", e)
            Toast.makeText(this, "Initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableNFC() {
        statusTextView?.text = "📱 Waiting for NFC device..."
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.d("ReaderActivity", "NFC Tag Discovered!")
        runOnUiThread {
            statusTextView?.text = "🔐 Authenticating device..."
            receivedDataTextView?.text = "Establishing secure connection..."
        }

        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 5000

            // 1. App Select
            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess()) throw IOException("AID selection failed.")

            // --- MUTUAL AUTHENTICATION PHASE ---

            // A. Generate Session Key
            sessionKey = CryptoUtils.generateSessionKey()

            // B. Encrypt Session Key using the Target's Public Key
            val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey!!, CryptoUtils.getOtherPublicKey())

            // C. Sign the Encrypted Key using My Private Key
            val signature = CryptoUtils.rsaSign(encryptedKey, CryptoUtils.getMyPrivateKey())

            // D. Send Encrypted Key
            val sendKeyCmd = Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey)
            var authRes = isoDep.transceive(sendKeyCmd)
            if (!authRes.isSuccess()) throw IOException("Auth Step 1 (Key Exchange) failed.")

            // E. Send Signature
            val sendSigCmd = Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_SIG, signature)
            authRes = isoDep.transceive(sendSigCmd)
            if (!authRes.isSuccess()) throw IOException("Auth Step 2 (Signature Validation) failed.")

            // F. Verify the Handshake Acknowledgement from the Sender
            val encryptedAck = authRes.getData()
            val decryptedAck = CryptoUtils.xorEncryptDecrypt(encryptedAck, sessionKey!!)
            if (String(decryptedAck, Charsets.UTF_8) != "AUTH_OK") {
                throw IOException("Authentication rejected by the sender.")
            }

            runOnUiThread {
                statusTextView?.text = "Secure connection established"
                receivedDataTextView?.text = "Requesting patient file..."
            }

            // --- DATA TRANSFER PHASE ---

            // 2. Request File Info
            response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw IOException("Failed to get metadata.")

            // DECRYPT the metadata payload using the session key!
            val encryptedMetadata = response.getData()
            val metadataPayload = CryptoUtils.xorEncryptDecrypt(encryptedMetadata, sessionKey!!)
            val transferMode = String(metadataPayload.copyOfRange(0, 1), Charsets.UTF_8)

            when (transferMode) {
                "T" -> handleTextReception(metadataPayload.copyOfRange(1, metadataPayload.size))
                "F" -> handleFileReception(isoDep, metadataPayload.copyOfRange(1, metadataPayload.size))
                "M" -> handleMultiFileReception(isoDep)
                else -> throw IOException("Unknown transfer mode: $transferMode")
            }

        } catch (e: IOException) {
            Log.e("ReaderActivity", "Error: ${e.message}", e)
            runOnUiThread {
                statusTextView?.text = "Connection Error"
                receivedDataTextView?.text = e.message ?: "Unknown error"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try { isoDep.close() } catch (e: IOException) { }
            sessionKey = null // Clear the session key from memory for security
        }
    }

    private fun handleFileReception(isoDep: IsoDep, fileInfoPayload: ByteArray) {
        val fileSize = ByteBuffer.wrap(fileInfoPayload.copyOfRange(0, 4)).int
        val fileName = String(fileInfoPayload.copyOfRange(4, fileInfoPayload.size), Charsets.UTF_8)

        Log.d("ReaderActivity", "Receiving file: $fileName, Size=$fileSize bytes")
        runOnUiThread { receivedDataTextView?.text = "📥 Downloading: $fileName\nSize: $fileSize bytes" }

        val tempFile = File(cacheDir, fileName)
        var receivedBytes = 0

        try {
            FileOutputStream(tempFile).use { fos ->
                var chunkCount = 0
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val response = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!response.isSuccess()) break

                    // DECRYPT the incoming file chunk!
                    val encryptedChunk = response.getData()
                    val decryptedChunk = CryptoUtils.xorEncryptDecrypt(encryptedChunk, sessionKey!!)

                    fos.write(decryptedChunk)
                    receivedBytes += decryptedChunk.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    runOnUiThread {
                        statusTextView?.text = "$receivedBytes / $fileSize bytes ($progress%)"
                    }
                }
            }

            displayFileInView(tempFile)
            runOnUiThread {
                statusTextView?.text = "Transfer Securely Completed"
            }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun handleMultiFileReception(isoDep: IsoDep) {
        val fileName = "aggregated_reports.txt"
        val tempFile = File(cacheDir, fileName)

        try {
            val response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw IOException("Failed to get file metadata.")

            // DECRYPT metadata
            val metadataPayload = CryptoUtils.xorEncryptDecrypt(response.getData(), sessionKey!!)
            val fileSize = ByteBuffer.wrap(metadataPayload.copyOfRange(0, 4)).int
            val receivedFileName = String(metadataPayload.copyOfRange(4, metadataPayload.size), Charsets.UTF_8)

            runOnUiThread {
                statusTextView?.text = "Streaming Secure Data..."
                receivedDataTextView?.text = "Receiving: $receivedFileName\nSize: $fileSize bytes"
            }

            var receivedBytes = 0
            var chunkCount = 0

            FileOutputStream(tempFile).use { fos ->
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val chunkResponse = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!chunkResponse.isSuccess()) break

                    // DECRYPT file chunk
                    val encryptedChunk = chunkResponse.getData()
                    val decryptedChunk = CryptoUtils.xorEncryptDecrypt(encryptedChunk, sessionKey!!)

                    fos.write(decryptedChunk)
                    receivedBytes += decryptedChunk.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    runOnUiThread {
                        statusTextView?.text = "$receivedBytes / $fileSize bytes ($progress%)"
                    }
                }
            }

            displayFileInView(tempFile)
            runOnUiThread { statusTextView?.text = "Transfer Securely Completed" }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val receivedString = String(payload, Charsets.UTF_8)
        runOnUiThread {
            statusTextView?.text = "Text received securely"
            receivedDataTextView?.text = receivedString
            scrollView?.post { scrollView?.scrollTo(0, 0) }
        }
    }

    private fun displayFileInView(file: File) {
        try {
            val content = file.readText(Charsets.UTF_8)

            SessionCache.processScannedData(content)

            runOnUiThread {
                receivedDataTextView?.text = content
                statusTextView?.text = "Patient Loaded! You may now go back."
                scrollView?.post { scrollView?.scrollTo(0, 0) }
                Toast.makeText(this, "Patient Data Cached. Press Back to update vitals.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error displaying file: ${e.message}", e)
        }
    }

    private fun ByteArray.isSuccess(): Boolean =
        this.size >= 2 && this.takeLast(2) == Utils.SELECT_OK_SW.toList()

    private fun ByteArray.getData(): ByteArray =
        this.copyOfRange(0, this.size - 2)

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }
}