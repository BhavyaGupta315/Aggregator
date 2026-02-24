package com.example.aggregator

import android.content.Context
import android.provider.Settings.Secure
import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("date") val date: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("content") val content: String,
    @SerializedName("receivedAt") val receivedAt: String
)

data class ReportResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("id") val id: String?
)

interface HealthApiService {
    @POST("api/reports")
    suspend fun syncReport(@Body request: ReportRequest): ReportResponse
}
class SyncRepository {
    private val BASE_URL = "https://nursing-backend-vp5o.onrender.com"
    private var apiService: HealthApiService

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(HealthApiService::class.java)
    }
    suspend fun syncLatestReport(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val appFilesDir = context.getExternalFilesDir(null) ?: return@withContext Result.failure(Exception("No storage"))
            val nursingDir = File(appFilesDir, "NursingDevice")
            if (!nursingDir.exists()) return@withContext Result.failure(Exception("No NursingDevice folder"))

            // Search across ALL date folders to find the single most recently modified .txt file
            val lastUpdatedFile = nursingDir.listFiles()
                ?.filter { it.isDirectory }                // Look into all date folders (e.g., 23-02, 24-02)
                ?.flatMap { it.listFiles()?.toList() ?: emptyList() } // Get all files inside them
                ?.filter { it.isFile && it.extension == "txt" }       // Only look for text files
                ?.maxByOrNull { it.lastModified() }         // Find the one that was edited most recently

            if (lastUpdatedFile == null) {
                return@withContext Result.failure(Exception("No report files found in any folder"))
            }

            // Use the parent folder's name as the date for the database record
            val date = lastUpdatedFile.parentFile?.name ?: "Unknown Date"
            val content = lastUpdatedFile.readText()
            val fileName = lastUpdatedFile.name

            val deviceId = Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
            val receivedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

            val request = ReportRequest(deviceId, date, fileName, content, receivedAt)
            val response = apiService.syncReport(request)

            if (response.success) {
                Result.success("Synced ${fileName} from ${date}: ${response.message}")
            } else {
                Result.failure(Exception(response.message ?: "Sync failed"))
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Sync error", e)
            Result.failure(e)
        }
    }
}