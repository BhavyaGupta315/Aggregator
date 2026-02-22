package com.example.aggregator

import java.io.ByteArrayOutputStream

object Utils {

    // --- NFC Protocol Status Words (SW) ---
    val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
    val UNKNOWN_CMD_SW = byteArrayOf(0x00.toByte(), 0x00.toByte())
    val FILE_NOT_READY_SW = byteArrayOf(0x6A.toByte(), 0x88.toByte()) // Data not found/ready

    // --- Application ID (AID) for your HCE service ---
    // This is the command the reader sends to connect to your app.
    val SELECT_APD = byteArrayOf(
        0x00.toByte(), // CLA
        0xA4.toByte(), // INS
        0x04.toByte(), // P1
        0x00.toByte(), // P2
        0x07.toByte(), // LC
        0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, // Your Application ID
        0x00.toByte()  // LE
    )

    // --- New Two-Phase Protocol Commands ---
    // These are simple, human-readable commands converted to byte arrays.

    // Command from Reader to Sender to get file metadata (size and MIME type)
    val GET_FILE_INFO_COMMAND = "GET_FILE_INFO".toByteArray(Charsets.UTF_8)

    // Command from Reader to Sender to get the next chunk of file data
    val GET_NEXT_DATA_CHUNK_COMMAND = "GET_NEXT_CHUNK".toByteArray(Charsets.UTF_8)

    /**
     * Concatenates multiple byte arrays into a single byte array.
     */
    fun concatArrays(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        arrays.forEach { out.write(it) }
        return out.toByteArray()
    }
}
