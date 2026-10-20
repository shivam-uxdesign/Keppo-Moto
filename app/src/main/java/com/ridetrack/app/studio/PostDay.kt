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
import com.ridetrack.app.MainActivity
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One post a day: which ready video is today's, and when the rider usually posts. Pure, unit-tested. */
object PostDay {
    /** Videos older than this aren't lined up any more (a ride from a month ago is old news). */
    const val FRESH_MS = 30 * 86_400_000L

    /** When nothing was shared yet: 7 PM. */
    const val DEFAULT_HOUR = 19

    /**
     * Ready videos in the order they're posted, one a day: not shared, made in the last 30 days,
     * oldest first, ones the rider skipped after the others (in the order skipped); a series'
     * episodes always in episode order.
     */
    fun queue(reels: List<ReelProject>, skipped: Map<String, Long>, now: Long): List<ReelProject> {
        // A Storyboard template with shots still to fill isn't a video yet.
        val ready = reels.filter { it.deletedAt == null && !it.posted && now - it.createdAt <= FRESH_MS && !(it.template != null && com.ridetrack.app.studio.storyboard.TemplateBuilder.placeholders(it.plan).isNotEmpty()) }
            .sortedWith(compareBy<ReelProject> { skipped[it.id] ?: 0L }.thenBy { it.createdAt })
        // Each series' episodes take its places in the line, in episode order.
        val bySeries = ready.filter { it.seriesId != null }.groupBy { it.seriesId }.mapValues { (_, v) -> ArrayDeque(v.sortedBy { it.episode }) }
        return ready.map { p -> p.seriesId?.let { bySeries[it]?.removeFirst() } ?: p }
    }

    /** The hour the rider usually shares (the middle of their last 10 shares), or 7 PM. */
    fun usualHour(postedAt: List<Long>, zone: ZoneId = ZoneId.systemDefault()): Int {
        val hours = postedAt.sortedDescending().take(10).map { Instant.ofEpochMilli(it).atZone(zone).hour }.sorted()
        return if (hours.isEmpty()) DEFAULT_HOUR else hours[hours.size / 2]
    }

    /** The next time it's [hour]:00, today if that's still ahead, else tomorrow. */
    fun nextAt(now: Long, hour: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val today = t.toLocalDate().atTime(hour, 0).atZone(zone)
        return (if (today.toInstant().toEpochMilli() > now) today else today.plusDays(1)).toInstant().toEpochMilli()
    }

    fun day(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}

/** Today's post, what was skipped, and the daily reminder's switch; on this phone. */
class PostPlan(private val context: Context) {
    private val prefs = context.getSharedPreferences("studio_post_day", Context.MODE_PRIVATE)

    /** "A post a day" (on by default): today's post on Home and a reminder at the usual time. */
    var on: Boolean
        get() = prefs.getBoolean(ON, true)
        set(v) {
            prefs.edit().putBoolean(ON, v).apply()
            if (v) TodayPostWorker.schedule(context) else TodayPostWorker.cancel(context)
        }

    fun skipped(): Map<String, Long> = prefs.all.filterKeys { it.startsWith(SKIP) }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix(SKIP) to it } }.toMap()

    /** Not today: it goes to the back of the line. */
    fun skip(id: String) = prefs.edit().putLong(SKIP + id, System.currentTimeMillis()).apply()

    fun queue(reels: List<ReelProject>, now: Long = System.currentTimeMillis()): List<ReelProject> = PostDay.queue(reels, skipped(), now)

    /** Today's post: the one picked today while it's still waiting, else the first in line (kept for the day). */
    fun today(reels: List<ReelProject>, now: Long = System.currentTimeMillis()): ReelProject? {
        val day = PostDay.day(now).toString()
        val line = queue(reels, now)
        prefs.getString(TODAY, null)?.split('|')?.takeIf { it.size == 2 && it[0] == day }?.let { (_, id) ->
            line.firstOrNull { it.id == id }?.let { return it }
        }
        return line.firstOrNull()?.also { prefs.edit().putString(TODAY, "$day|${it.id}").apply() }
    }

    /** "Swap": today's goes to the back of the line; the next one is today's. */
    fun swap(reels: List<ReelProject>, now: Long = System.currentTimeMillis()): ReelProject? {
        today(reels, now)?.let { skip(it.id) }
        prefs.edit().remove(TODAY).apply()
        return today(reels, now)
    }

    private companion object {
        const val ON = "on"
        const val TODAY = "today"
        const val SKIP = "skip_"
    }
}

/** Once a day at the rider's usual time: "Today's post", opening it full screen to share. */
class TodayPostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RideTrackApp).container
        if (!c.postPlan.on) return Result.success()
        c.postPlan.today(c.reels.reels.value)?.let { notify(it, c.postPlan.queue(c.reels.reels.value).size) }
        schedule(applicationContext, next = true)
        return Result.success()
    }

    private fun notify(p: ReelProject, waiting: Int) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Today's post", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "One ready video a day, at the time you usually post" })
        }
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_RIDE)
            .putExtra(MainActivity.EXTRA_REVIEW, p.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, NOTIFICATION, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = if (p.seriesId != null) "${p.series} · Ep ${p.episode}: " else ""
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Today's post: $label${p.title}")
            .setContentText("Tap to watch and share; the caption is copied" + if (waiting > 1) " · ${waiting - 1} more waiting" else "")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION, n)
    }

    companion object {
        private const val NAME = "studio-today-post"
        private const val CHANNEL = "studio_today"
        private const val NOTIFICATION = 4_207

        /** At the next usual posting time. [next]: from the reminder itself, after it ran. */
        fun schedule(context: Context, next: Boolean = false) {
            val c = (context.applicationContext as? RideTrackApp)?.container ?: return
            if (!c.postPlan.on) return
            val now = System.currentTimeMillis()
            val hour = PostDay.usualHour(c.reels.reels.value.mapNotNull { it.postedAt })
            // From the reminder itself: tomorrow's, not the one that's just gone off.
            val at = PostDay.nextAt(now + if (next) 60 * 60_000L else 0L, hour)
            val req = OneTimeWorkRequestBuilder<TodayPostWorker>().setInitialDelay(at - now, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (next) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
