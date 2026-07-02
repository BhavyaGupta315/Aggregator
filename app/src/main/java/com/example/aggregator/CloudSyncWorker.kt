package com.example.aggregator

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The DB is encrypted with an in-memory, PIN-derived key. If the app process
        // was killed since the last unlock, the key is gone and we cannot read the DB.
        // Skip quietly and retry later (the user will unlock again on next app open).
        if (!AggregatorSession.isUnlocked) {
            return Result.retry()
        }
        return try {
            SyncRepository().syncPendingRecords(applicationContext).fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() }
            )
        } catch (e: IllegalStateException) {
            // DB locked mid-run — retry after next unlock.
            Result.retry()
        }
    }
}
