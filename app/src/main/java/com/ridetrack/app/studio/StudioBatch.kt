package com.ridetrack.app.studio

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ridetrack.app.AppContainer
import com.ridetrack.app.RideTrackApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Making a ride's ideas in one go, from the Studio tab or overnight. */
object StudioBatch {
    /**
     * Queues one video for every card of [rideId] with nothing made yet (the idea leading it, not its
     * variations; Show all's ideas too), leaving out ones already waiting. Returns how many were queued.
     */
    suspend fun makeAll(c: AppContainer, rideId: String, service: Boolean): Int {
        val engine = StudioEngine(c, rideId)
        if (engine.load() != null) return 0
        val scripts = engine.loadPlan() ?: return 0
        val made = engine.madeIdeas()
        val waiting = c.reelMaker.state.value.let { m -> m.next.map { it.second }.toSet() + listOfNotNull(m.current) }
        val s = StudioState(
            title = engine.card?.title.orEmpty(), options = c.studio.options,
            musicUri = c.studio.music?.first?.let(android.net.Uri::parse), musicName = c.studio.music?.second,
        )
        val jobs = IdeaGroups.group(engine.pieces(scripts)) { it.script }
            .filter { g -> g.all.none { it.script.title in made } }
            .map { it.main }
            .filter { it.script.title !in waiting }
            .mapNotNull { engine.job(it, s) }
        c.reelMaker.enqueue(jobs, service)
        return jobs.size
    }
}

/**
 * Overnight, while the phone charges: every idea from the last few days' rides that isn't made
 * yet is made, so they're waiting in Your Reels in the morning. On in Studio › Settings.
 */
class OvernightMakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RideTrackApp).container
        if (!c.studio.makeOvernight) return Result.success()
        val since = System.currentTimeMillis() - DAYS * 86_400_000L
        val rides = c.rides.observeCompleted().first().filter { it.startTimeMillis >= since }
        var n = 0
        rides.forEach { r -> n += runCatching { StudioBatch.makeAll(c, r.id, service = false) }.onFailure { c.errors.record("Studio overnight", "Couldn't queue a ride's ideas", it) }.getOrDefault(0) }
        // Stay running while they're made; what's left is made the next time the app starts.
        if (n > 0) withTimeoutOrNull(50 * 60_000L) { while (!c.reelMaker.state.value.idle) delay(5_000) }
        return Result.success()
    }

    companion object {
        private const val NAME = "studio-overnight"
        private const val DAYS = 3

        /** On: once a day, only while charging and idle. Off: cancelled. */
        fun set(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<OvernightMakeWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
