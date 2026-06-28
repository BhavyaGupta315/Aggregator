package com.example.aggregator

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = SyncRepository().syncPendingRecords(applicationContext)
        return result.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
