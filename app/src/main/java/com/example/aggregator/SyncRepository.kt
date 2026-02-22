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
    private lateinit var apiService: HealthApiService

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

    suspend fun syncLatestReport(context: Context): Result<String> = withContext(Dispatchers.IO){
        try {
            val appFilesDir = context.getExternalFilesDir(null) ?: return@withContext Result.failure(Exception("No storage"))
            val nursingDir = File(appFilesDir, "NursingDevice")
            if (!nursingDir.exists()) return@withContext Result.failure(Exception("No NursingDevice folder"))

            // Find the latest date folder
            val latestDateFolder = nursingDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name } ?: return@withContext Result.failure(Exception("No date folders"))

            val masterFile = File(latestDateFolder, "aggregated_reports.txt")
            if (!masterFile.exists()) return@withContext Result.failure(Exception("No aggregated_reports.txt"))

            val date = latestDateFolder.name
            val content = masterFile.readText()
            val deviceId = Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
            val receivedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

            val request = ReportRequest(deviceId, date, "aggregated_reports.txt", content, receivedAt)
            val response = apiService.syncReport(request)

            if (response.success) {
                Result.success("Synced ${date}: ${response.message}")
            } else {
                Result.failure(Exception(response.message ?: "Sync failed"))
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Sync error", e)
            Result.failure(e)
        }
    }
}