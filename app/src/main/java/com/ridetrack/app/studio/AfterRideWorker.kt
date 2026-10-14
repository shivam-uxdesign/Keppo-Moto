package com.ridetrack.app.studio

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ridetrack.app.MainActivity
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.transcribe.GeminiQuota
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * After a ride: reads what was said, asks Gemini what to make, says so in a notification and
 * makes the top suggestion, so it's waiting in Your Reels. Runs as soon as the ride ends when the
 * battery is above half; below that it waits for Wi-Fi (or any network, if the rider allows).
 */
class AfterRideWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RideTrackApp).container
        val rideId = inputData.getString(RIDE) ?: return Result.success()
        // Automatic ideas and Reels are Pro (free riders make their weekly Reel themselves).
        if (!c.studio.autoSuggest || !c.entitlements.isPro) return Result.success()
        val engine = StudioEngine(c, rideId)
        if (engine.load() != null || engine.bits.size < 2) return Result.success()
        // Already suggested (Studio was opened first): nothing to do.
        if (engine.loadPlan() != null) return Result.success()
        return try {
            if (c.transcripts.available && c.studio.options.captions) {
                val read = engine.readCaptions()
                if (read.unreachable != null) return retryOrGiveUp(c)
            }
            val title = engine.card?.title.orEmpty()
            when (val r = engine.suggest(title)) {
                is Suggested.Unreachable -> retryOrGiveUp(c)
                Suggested.Busy -> {
                    // Gemini's free limit: try again when it's free.
                    val at = GeminiQuota.freeAt(GeminiQuota.STORY) ?: (System.currentTimeMillis() + 60 * 60_000L)
                    schedule(applicationContext, rideId, delayMs = (at - System.currentTimeMillis()).coerceAtLeast(60_000L) + 60_000L)
                    c.errors.warn("Studio after ride", "Gemini's free limit is used up: suggestions for \"$title\" wait until ${GeminiQuota.clock(at)}")
                    Result.success()
                }
                is Suggested.Scripts -> {
                    // What's in the new clips, so search finds it (while Gemini's allowance lasts).
                    runCatching { ClipSeen.look(c, engine.clips, limit = 24) }
                    val pieces = engine.pieces(r.scripts)
                    // The top 3: a long reel, the best moment and your story, each from its own card.
                    val fs = engine.footage().associateBy { it.momentId }
                    val talks = { sc: Script ->
                        sc.sections.any { sec -> sec.shots.any { sh -> fs[sh.clip]?.lines?.any { l -> l.startMs < sh.outMs && l.endMs > sh.inMs } == true } }
                    }
                    val groups = IdeaGroups.group(pieces) { it.script }
                    val top = if (c.studio.autoMakeTop) TopSlots.pick(groups, { it.script }, talks) else emptyList()
                    // Each active series looks for its episodes in the ride (one Gemini request each).
                    val found = SeriesWork.find(c, engine, rideId).filter { it.episodes.isNotEmpty() }
                    notifyReady(
                        rideId, title, groups.size,
                        top.map { (slot, pc) -> "${slot.label}: ${pc.script.title}" } + found.flatMap { f -> f.episodes.map { "${f.series.name}: ${it.title}" } },
                    )
                    val episodes = found.flatMap { it.episodes }
                    if (top.isNotEmpty() || episodes.isNotEmpty()) {
                        val s = StudioState(
                            title = title, options = c.studio.options,
                            musicUri = c.studio.music?.first?.let(android.net.Uri::parse), musicName = c.studio.music?.second,
                        )
                        val jobs = top.mapNotNull { (_, pc) -> engine.job(pc, s) }
                        c.reelMaker.enqueue(jobs, service = false)
                        val queued = jobs.size + SeriesWork.make(c, engine, episodes, service = false)
                        if (queued > 0) {
                            // Stay running while they're made (the app may be in the background); what's
                            // left when the system stops this is made the next time the app starts.
                            withTimeoutOrNull(MAKE_WAIT_MS) { while (!c.reelMaker.state.value.idle) delay(2_000) }
                        }
                    }
                    Result.success()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: FirebaseTranscriber.Busy) {
            Result.retry()
        } catch (e: Exception) {
            c.errors.record("Studio after ride", "Couldn't suggest what to make from the ride", e)
            retryOrGiveUp(c)
        }
    }

    private fun retryOrGiveUp(c: com.ridetrack.app.AppContainer): Result =
        if (runAttemptCount < 3) Result.retry() else Result.success().also {
            c.errors.warn("Studio after ride", "Gave up suggesting after ${runAttemptCount + 1} tries: open the ride's Studio to try again")
        }

    @SuppressLint("MissingPermission")
    private fun notifyReady(rideId: String, title: String, count: Int, making: List<String>) {
        val ctx = applicationContext
        if (count == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Studio suggestions", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "What to make from a ride, after it ends" })
        }
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_RIDE)
            .putExtra("rideId", rideId)
            .putExtra(MainActivity.EXTRA_STUDIO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, rideId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(
                if (making.isNotEmpty()) "Making ${making.size} from $title"
                else if (count == 1) "1 idea for $title" else "$count ideas for $title",
            )
            .setContentText(if (making.isNotEmpty()) making.joinToString(" · ") else "Open Studio to make them")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (making.isNotEmpty()) making.joinToString("\n") + "\n$count ideas in all" else "Open Studio to make them"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(rideId.hashCode(), n)
    }

    companion object {
        private const val RIDE = "ride"
        private const val CHANNEL = "studio_ready"
        private const val MAKE_WAIT_MS = 9 * 60_000L

        /**
         * After [rideId] ends: straight away above 50% battery, otherwise on Wi-Fi (or any network
         * when the rider allows mobile data).
         */
        fun schedule(context: Context, rideId: String, delayMs: Long = 0L) {
            val c = (context.applicationContext as RideTrackApp).container
            if (!c.studio.autoSuggest || !c.entitlements.isPro) return
            val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val network = if (battery > 50 || c.studio.autoMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val req = OneTimeWorkRequestBuilder<AfterRideWorker>()
                .setInputData(workDataOf(RIDE to rideId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("studio-after-ride-$rideId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
