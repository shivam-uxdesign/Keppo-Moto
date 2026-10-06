package com.ridetrack.app.ride

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.ridetrack.app.data.BikeRepository
import com.ridetrack.app.data.RideRepository
import com.ridetrack.app.moments.MomentLog
import com.ridetrack.app.moments.MomentRepository
import com.ridetrack.telemetry.model.Ride
import java.io.File

/**
 * A ride the app didn't get to finish (killed, crashed, or the phone died). Recent ones carry on
 * as the same ride; older ones are saved as they are. Why the app stopped goes into the ride's
 * moments log.
 */
class RideContinuation(
    private val context: Context,
    private val rides: RideRepository,
    private val bikes: BikeRepository,
    private val moments: MomentRepository,
    private val session: RideSessionManager,
) {
    /** The newest unfinished ride and when it was last written, if any. */
    suspend fun latest(): Pair<Ride, Long>? = rides.unfinished().maxByOrNull { it.second }

    /** Carries on the newest unfinished ride if it stopped less than [withinMillis] ago. */
    suspend fun continueIfRecent(withinMillis: Long, how: String): String? {
        val (ride, last) = latest() ?: return null
        val gap = System.currentTimeMillis() - last
        if (gap > withinMillis) return null
        val bike = bikes.get(ride.bikeId) ?: return null
        val id = session.continueRide(ride, last, bike) ?: return null
        MomentLog.append(logFile(ride.id), "ride carried on ($how) after ${gap / 1000} s without recording")
        return id
    }

    /** Saves unfinished rides older than [olderThanMillis] (and every one but [keep]). */
    suspend fun saveStale(olderThanMillis: Long, keep: String? = null) {
        val now = System.currentTimeMillis()
        rides.unfinished().forEach { (ride, last) ->
            if (ride.id != keep && (keep != null || now - last > olderThanMillis) && session.active.value?.rideId != ride.id) {
                rides.recover(ride, RideNames.forStart(ride.startTimeMillis))
                MomentLog.append(logFile(ride.id), "ride saved automatically (the app had stopped)")
            }
        }
    }

    /**
     * On launch: why the app last stopped (crash, low memory, killed by the system…), written to
     * the unfinished ride's log so the export shows it. Only reasons not reported before.
     */
    fun reportExit(lastReportedMillis: Long, rideId: String?): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService<ActivityManager>() ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()
            ?.filter { it.timestamp > lastReportedMillis }
            ?.maxByOrNull { it.timestamp } ?: return null
        val crash = File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.let { f ->
            runCatching { f.readText().lines().take(12).joinToString(" | ") }.getOrNull().also { f.delete() }
        }
        val line = "app last stopped: ${reasonName(info.reason)}" +
            (info.description?.let { " ($it)" } ?: "") +
            " · importance ${info.importance} · memory ${info.pss / 1024} MB" +
            (crash?.let { " · crash: $it" } ?: "")
        if (rideId != null) MomentLog.append(logFile(rideId), line)
        return info.timestamp
    }

    private fun logFile(rideId: String) = File(moments.dir(rideId), MomentLog.FILE_NAME)

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native)"
        ApplicationExitInfo.REASON_ANR -> "NOT RESPONDING"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "TOO MUCH BATTERY/CPU"
        ApplicationExitInfo.REASON_SIGNALED -> "KILLED BY THE SYSTEM"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "STOPPED BY THE USER (or the system for them)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "FORCE STOPPED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION CHANGED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXITED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "STARTUP FAILED"
        else -> "reason $r"
    }

    companion object {
        /** The stack trace of an uncaught crash, written by the app's crash handler. */
        const val CRASH_FILE = "last-crash.txt"
        /** The service restarted by Android after the app was killed: carry on within this. */
        const val RESTART_WINDOW_MILLIS = 10 * 60_000L
        /** Opening the app after it stopped (or the phone died): offer to carry on within this. */
        const val CONTINUE_WINDOW_MILLIS = 30 * 60_000L
    }
}
