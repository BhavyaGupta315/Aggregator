package com.example.aggregator

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.compareTo
import kotlin.math.min

class MyHostApduService : HostApduService() {

    private enum class AuthState { IDLE, KEY_RECEIVED, AUTHENTICATED }
    private var currentAuthState = AuthState.IDLE
    private var sessionKey: ByteArray? = null
    private var encryptedKeyBuffer: ByteArray? = null
    private var transferMode = "NONE"
    private var textContent: String? = null
    private var fileContent: ByteArray? = null
    private var fileMimeType: String? = null
    private var fileChunkOffset: Int = 0
    private var fileQueue: MutableList<FileData> = mutableListOf()
    private var currentFileIndex: Int = 0

    companion object {
        private var sharedTransferMode = "NONE"
        private var sharedTextContent: String? = null
        private var sharedFileContent: ByteArray? = null
        private var sharedFileMimeType: String? = null
        private var sharedFileQueue: MutableList<FileData> = mutableListOf()

        fun setSingleTextForTransfer(text: String) {
            sharedTransferMode = "TEXT"
            sharedTextContent = text
            sharedFileContent = null
            sharedFileQueue.clear()
        }
        // --- Class-level static references for sending operations ---
        fun setMultipleFilesForTransfer(files: List<FileData>) {
            sharedTransferMode = "MULTI_FILE"
            sharedFileQueue.clear()
            sharedFileQueue.addAll(files)
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null

            Log.d("HCE_SERVICE", "Service armed for APPENDED FILE transfer. Total bytes: ${files.sumOf { it.content.size }}")
            if (files.isNotEmpty()) {
                Log.d("HCE_SERVICE", "Sending file: ${files[0].name}")
            }
        }
        fun resetTransferState() {
            sharedTransferMode = "NONE"
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null
            sharedFileQueue.clear()
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // Step 1: Selection
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            currentAuthState = AuthState.IDLE
            sessionKey = null

            // CRITICAL FIX 1: Reset indices on every new NFC tap
            currentFileIndex = 0
            fileChunkOffset = 0

            notifyUI("Step 1: Connection Established")
            return Utils.SELECT_OK_SW
        }

        // Step 2: Key Exchange
        val cmdKey = CryptoUtils.CMD_AUTH_SEND_KEY
        if (commandApdu.take(cmdKey.size).toByteArray().contentEquals(cmdKey)) {
            encryptedKeyBuffer = commandApdu.drop(cmdKey.size).toByteArray()
            currentAuthState = AuthState.KEY_RECEIVED
            notifyUI("Step 2: Key Received")
            return Utils.SELECT_OK_SW
        }

        // Step 3: Signature & Final Auth
        val cmdSig = CryptoUtils.CMD_AUTH_SEND_SIG
        if (commandApdu.take(cmdSig.size).toByteArray().contentEquals(cmdSig)) {
            if (currentAuthState != AuthState.KEY_RECEIVED) return Utils.UNKNOWN_CMD_SW

            val signature = commandApdu.drop(cmdSig.size).toByteArray()
            val encryptedKey = encryptedKeyBuffer ?: return Utils.UNKNOWN_CMD_SW

            val verified = CryptoUtils.rsaVerify(encryptedKey, signature, CryptoUtils.getOtherPublicKey())
            if (verified) {
                sessionKey = CryptoUtils.rsaDecrypt(encryptedKey, CryptoUtils.getMyPrivateKey())
                currentAuthState = AuthState.AUTHENTICATED

                // CRITICAL FIX 2: Load the data and enforce reset at the moment of authentication
                this.transferMode = sharedTransferMode
                this.textContent = sharedTextContent
                this.fileQueue = sharedFileQueue.toMutableList()
                this.currentFileIndex = 0
                this.fileChunkOffset = 0

                notifyUI("Step 3: Authenticated Securely")

                val ack = CryptoUtils.xorEncryptDecrypt("AUTH_OK".toByteArray(), sessionKey!!)
                return Utils.concatArrays(ack, Utils.SELECT_OK_SW)
            }
            notifyUI("Authentication Failed")
            return Utils.UNKNOWN_CMD_SW
        }

        // Only process data if Authenticated
        if (currentAuthState != AuthState.AUTHENTICATED) return Utils.FILE_NOT_READY_SW

        // Sync state and handle transfer
        transferMode = sharedTransferMode
        textContent = sharedTextContent

        val rawResponse = when (transferMode) {
            "TEXT" -> handleTextTransfer(commandApdu)
            "FILE" -> handleFileTransfer(commandApdu)
            "MULTI_FILE" -> handleAppendedFileTransfer(commandApdu)
            else -> Utils.FILE_NOT_READY_SW
        }

        // Encrypt Data Responses
        return if (rawResponse.size > 2) {
            val data = rawResponse.copyOfRange(0, rawResponse.size - 2)
            val encryptedData = CryptoUtils.xorEncryptDecrypt(data, sessionKey!!)
            Utils.concatArrays(encryptedData, Utils.SELECT_OK_SW)
        } else {
            rawResponse
        }
    }

    private fun handleTextTransfer(commandApdu: ByteArray): ByteArray {
        if (!Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND)) {
            return Utils.UNKNOWN_CMD_SW
        }

        val text = textContent ?: return Utils.FILE_NOT_READY_SW
        val textBytes = text.toByteArray(Charsets.UTF_8)

        // Metadata Payload for Text: [1 byte for mode ('T')] + [N bytes for the text itself]
        val modeByte = "T".toByteArray(Charsets.UTF_8)
        val textPayload = Utils.concatArrays(modeByte, textBytes)

        Log.d("HCE_SERVICE", "Sending text payload of size ${textPayload.size}")
        return Utils.concatArrays(textPayload, Utils.SELECT_OK_SW)
    }

    private fun handleFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val mimeBytes = fileMimeType?.toByteArray(Charsets.UTF_8) ?: return Utils.FILE_NOT_READY_SW

                // Metadata Payload for File: [1 byte for mode ('F')] + [4 bytes for size] + [N bytes for MIME]
                val modeByte = "F".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(content.size).array()
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, mimeBytes)

                Log.d("HCE_SERVICE", "Sending file metadata payload.")
                return Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }

            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val remaining = content.size - fileChunkOffset
                if (remaining <= 0) return Utils.FILE_NOT_READY_SW

                val chunkSize = min(remaining, 245)
                val chunk = content.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize

                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    private fun handleAppendedFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                if (currentFileIndex >= fileQueue.size) {
                    Log.d("HCE_SERVICE", "All files sent. Ending transfer.")
                    return Utils.concatArrays(byteArrayOf(), Utils.SELECT_OK_SW)
                }

                val currentFile = fileQueue[currentFileIndex]
                val fileNameBytes = currentFile.name.toByteArray(Charsets.UTF_8)
                val modeByte = "M".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(currentFile.content.size).array()
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, fileNameBytes)

                fileChunkOffset = 0
                Log.d(
                    "HCE_SERVICE",
                    "Sending appended file: ${currentFile.name} (Size: ${currentFile.content.size} bytes)"
                )
                return Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }

            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                if (currentFileIndex >= fileQueue.size) return Utils.FILE_NOT_READY_SW

                val currentFile = fileQueue[currentFileIndex]
                val remaining = currentFile.content.size - fileChunkOffset

                if (remaining <= 0) {
                    // Single file transfer complete
                    currentFileIndex++
                    fileChunkOffset = 0
                    Log.d("HCE_SERVICE", "Appended file transfer complete.")
                    return Utils.SELECT_OK_SW
                }

                val chunkSize = min(remaining, 245)
                val chunk =
                    currentFile.content.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize

                Log.d(
                    "HCE_SERVICE",
                    "Sending chunk: $fileChunkOffset / ${currentFile.content.size}"
                )
                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    override fun onDeactivated(reason: Int) {
        currentAuthState = AuthState.IDLE
        sessionKey = null
    }
    private fun notifyUI(step: String) {
        val intent = Intent("NFC_AUTH_STEP")
        intent.putExtra("step_message", step)
        sendBroadcast(intent)
    }

}