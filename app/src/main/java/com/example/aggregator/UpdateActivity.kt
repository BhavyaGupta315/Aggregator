package com.example.aggregator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.IOException

class UpdateActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var transferProgressBar: ProgressBar

    // Safety flag to prevent crashes if NFC hits before UI is ready
    private var isUiReady = false

    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("step_message")
            if (isUiReady) statusText.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        statusText = findViewById(R.id.statusText)
        fileNameText = findViewById(R.id.fileNameText)
        transferProgressBar = findViewById(R.id.transferProgressBar)

        isUiReady = true // UI is now linked and safe to use
        statusText.text = "Waiting for NFC device..."
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (!isUiReady) return // Don't process if views aren't ready

        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 10000

            // 1. Handshake
            isoDep.transceive(Utils.SELECT_APD)

            val sessionKey = CryptoUtils.generateSessionKey()
            val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey, CryptoUtils.getOtherPublicKey())
            isoDep.transceive(Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey))

            val signature = CryptoUtils.rsaSign(encryptedKey, CryptoUtils.getMyPrivateKey())
            val authRes = isoDep.transceive(Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_SIG, signature))

            // 2. Verify Auth
            val decryptedAck = CryptoUtils.xorEncryptDecrypt(authRes.copyOfRange(0, authRes.size - 2), sessionKey)
            if (String(decryptedAck) == "AUTH_OK") {
                runOnUiThread { statusText.text = "Authenticated! Fetching Data..." }

                // 3. REQUEST THE FILE CONTENT
                fetchData(isoDep, sessionKey)
            }
        } catch (e: Exception) {
            runOnUiThread { statusText.text = "Error: ${e.message}" }
        } finally {
            isoDep.close()
        }
    }

    private fun fetchData(isoDep: IsoDep, sessionKey: ByteArray) {
        // Request Metadata
        val metaRes = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
        if (metaRes.size > 2) {
            val decryptedMeta = CryptoUtils.xorEncryptDecrypt(metaRes.copyOfRange(0, metaRes.size - 2), sessionKey)
            val patientData = String(decryptedMeta)

            runOnUiThread {
                statusText.text = "Patient Received Securely"
                fileNameText.text = patientData // Show the patient details here
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("NFC_AUTH_STEP")
        val receiverFlags = ContextCompat.RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(this, authReceiver, filter, receiverFlags)
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(authReceiver)
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }
}