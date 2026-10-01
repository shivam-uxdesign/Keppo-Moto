package com.ridetrack.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ridetrack.app.RideTrackApp
import java.util.concurrent.TimeUnit

/** Runs a backup, or downloads restored moments, in the background. Android retries it when it fails. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as RideTrackApp).container.backup
        val ok = when (inputData.getString(KEY_JOB)) {
            JOB_RESTORE_MEDIA -> repo.restoreMedia()
            else -> repo.backUp()
        }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_JOB = "job"
        private const val JOB_BACKUP = "backup"
        private const val JOB_RESTORE_MEDIA = "restore_media"
        private const val NOW = "keppo-backup-now"
        private const val DAILY = "keppo-backup-daily"
        private const val MEDIA = "keppo-restore-media"

        private fun constraints(allowMobileData: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Back up soon (after a ride, or "Back up now"). Replaces a waiting run rather than queueing another. */
        fun backUpSoon(context: Context, allowMobileData: Boolean) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(allowMobileData))
                .setInputData(workDataOf(KEY_JOB to JOB_BACKUP))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        /** A daily catch-up, e.g. for videos that waited for Wi-Fi or charging. */
        fun scheduleDaily(context: Context, allowMobileData: Boolean) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints(allowMobileData))
                .setInputData(workDataOf(KEY_JOB to JOB_BACKUP))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun restoreMedia(context: Context, allowMobileData: Boolean) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
                .setInputData(workDataOf(KEY_JOB to JOB_RESTORE_MEDIA))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MEDIA, ExistingWorkPolicy.KEEP, req)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(NOW)
                cancelUniqueWork(DAILY)
                cancelUniqueWork(MEDIA)
            }
        }
    }
}
