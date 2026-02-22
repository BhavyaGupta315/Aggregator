package com.example.aggregator

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.compareTo
import kotlin.math.min

class MyHostApduService : HostApduService() {
    private var transferMode = "NONE"
    private var textContent: String? = null
    private var fileContent: ByteArray? = null
    private var fileMimeType: String? = null
    private var fileChunkOffset: Int = 0
    private var fileQueue: MutableList<FileData> = mutableListOf()
    private var currentFileIndex: Int = 0

    companion object {

        // --- Class-level static references for sending operations ---
        private var sharedTransferMode = "NONE"
        private var sharedTextContent: String? = null
        private var sharedFileContent: ByteArray? = null
        private var sharedFileMimeType: String? = null
        private var sharedFileQueue: MutableList<FileData> = mutableListOf()

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
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            Log.d("HCE_SERVICE", "Application Selected.")
            fileChunkOffset = 0

            // Sync instance state with shared state
            transferMode = sharedTransferMode
            textContent = sharedTextContent
            fileContent = sharedFileContent
            fileMimeType = sharedFileMimeType
            fileQueue.clear()
            fileQueue.addAll(sharedFileQueue)

            return Utils.SELECT_OK_SW
        }

        return when (transferMode) {
            "TEXT" -> handleTextTransfer(commandApdu)
            "FILE" -> handleFileTransfer(commandApdu)
            "MULTI_FILE" -> handleAppendedFileTransfer(commandApdu)
            else -> {
                Log.w("HCE_SERVICE", "Received command but no data is ready (mode is NONE).")
                Utils.FILE_NOT_READY_SW
            }
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
        Log.d("HCE_SERVICE", "Deactivated (reason: $reason).")
        if (reason == DEACTIVATION_LINK_LOSS) {
            Log.d("HCE_SERVICE", "Link loss - keeping data for reconnection")
        } else {
            Log.d("HCE_SERVICE", "Major deactivation - resetting state")
            resetTransferState()
        }
    }

}