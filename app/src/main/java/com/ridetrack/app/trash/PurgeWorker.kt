package com.ridetrack.app.trash

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ridetrack.app.RideTrackApp
import java.util.concurrent.TimeUnit

/** Once a day: whatever has been in Recently deleted for 30 days is removed for good. */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as RideTrackApp).container.trash.purgeExpired()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PurgeWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("keppo-purge-deleted", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
