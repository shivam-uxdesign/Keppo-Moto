package com.ridetrack.app.studio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ridetrack.app.MainActivity
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import java.util.concurrent.TimeUnit

/**
 * A day after a Reel is shared: "How did it do?", opening the Reel to type its views and likes
 * (Studio leans towards what does well). Nothing if they were already added or it was deleted.
 */
class PostedReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RideTrackApp).container
        val id = inputData.getString(REEL) ?: return Result.success()
        val p = c.reels.get(id) ?: return Result.success()
        if (p.deletedAt != null || p.views != null) return Result.success()
        notify(p)
        return Result.success()
    }

    private fun notify(p: ReelProject) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "How your Reels did", NotificationManager.IMPORTANCE_LOW).apply { description = "A day after you share a Reel" })
        }
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_RIDE)
            .putExtra("rideId", p.rideId.orEmpty())
            .putExtra(MainActivity.EXTRA_STUDIO, true)
            .putExtra(MainActivity.EXTRA_REEL, p.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, p.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("How did “${p.title}” do?")
            .setContentText("Add its views from Instagram: Studio leans towards what works")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(p.id.hashCode(), n)
    }

    companion object {
        private const val REEL = "reel"
        private const val CHANNEL = "studio_posted"

        /** A day after [reelId] is shared (once per Reel). */
        fun schedule(context: Context, reelId: String) {
            val req = OneTimeWorkRequestBuilder<PostedReminderWorker>()
                .setInitialDelay(24, TimeUnit.HOURS)
                .setInputData(workDataOf(REEL to reelId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("studio-posted-$reelId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
