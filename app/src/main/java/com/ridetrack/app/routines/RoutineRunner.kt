package com.ridetrack.app.routines

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.diag.ErrorLog
import com.ridetrack.app.ride.RideSessionManager
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the rider's routines: on a ride's start and end, and on arriving at a place. Steps run
 * in order, a moment apart so apps don't fight over the screen. What a ride's routines change
 * (media volume, Do Not Disturb) is put back when the ride ends.
 *
 * Opening an app while Keppo Moto is in the background needs the pop-up permission (display
 * over other apps), which the HUD already asks for; without it the step becomes a notification
 * to tap instead.
 */
class RoutineRunner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val session: RideSessionManager,
    private val errors: ErrorLog,
) {
    private var arrival = ArrivalWatch()
    private var volumeBefore: Int? = null
    private var dndBefore: Int? = null

    fun watch(scope: CoroutineScope) {
        scope.launch {
            var prev: RideState = session.state.value
            session.state.collect { now ->
                val started = !prev.isActive && now is RideState.Recording
                val ended = prev.isActive && !now.isActive
                prev = now
                // Each routine runs on its own, so a long Wait doesn't hold up noticing the next change.
                if (started) {
                    arrival = ArrivalWatch()
                    scope.launch { run(scope, RoutineTrigger.RIDE_START) }
                }
                if (ended) scope.launch {
                    undo()
                    run(scope, RoutineTrigger.RIDE_END)
                }
            }
        }
        scope.launch {
            session.frame.collect { f ->
                if (!session.state.value.isActive) return@collect
                val lat = f?.latitude ?: return@collect
                val lon = f.longitude ?: return@collect
                val list = routines()
                if (list.none { it.enabled && RoutineTrigger.ARRIVE in it.triggers }) return@collect
                arrival.update(list, lat, lon, f.timeMillis).forEach { r -> scope.launch { runSteps(r, r.runnable(RoutineTrigger.ARRIVE)) } }
            }
        }
    }

    /** Runs [r]'s steps now, from the Routines page (never ends a ride). */
    suspend fun test(r: Routine) = runSteps(r, r.actions.filter { it != RoutineAction.EndRide }, remember = false)

    private suspend fun routines() = Routines.decode(settings.settings.first().routines)

    private suspend fun run(scope: CoroutineScope, trigger: RoutineTrigger) {
        routines().filter { it.enabled && trigger in it.triggers }.forEach { r -> scope.launch { runSteps(r, r.runnable(trigger)) } }
    }

    private suspend fun runSteps(r: Routine, steps: List<RoutineAction>, remember: Boolean = true) {
        val failed = ArrayList<String>()
        steps.forEachIndexed { k, a ->
            if (k > 0) delay(GAP_MILLIS)
            val ok = runCatching { withContext(Dispatchers.Main) { step(a, remember) } }
                .onFailure { errors.warn("Routines", "\"${r.name}\": couldn't ${a.label.lowercase()}", it) }
                .getOrDefault(false)
            if (!ok) failed += a.label
        }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH).format(java.util.Date())
        lastRun[r.id] = if (failed.isEmpty()) "Ran at $time" else "Ran at $time · didn't: ${failed.joinToString(", ")}"
        prefs().edit().putString(r.id, lastRun[r.id]).apply()
    }

    /** What happened when [id] last ran ("Ran at 18:02"), or null. */
    fun lastRun(id: String): String? = lastRun[id] ?: prefs().getString(id, null)
    private val lastRun = HashMap<String, String>()
    private fun prefs() = context.getSharedPreferences("routines_runs", Context.MODE_PRIVATE)

    /** True when the step did what it says (as far as Android lets us know). */
    private suspend fun step(a: RoutineAction, remember: Boolean): Boolean {
        val audio = context.getSystemService<AudioManager>()
        return when (a) {
            RoutineAction.ResumeMusic -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
            RoutineAction.PauseMusic -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
            RoutineAction.OpenMaps -> open(context.packageManager.getLaunchIntentForPackage(MAPS)
                ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")), "Maps")
            is RoutineAction.OpenApp -> context.packageManager.getLaunchIntentForPackage(a.pkg)?.let { open(it, a.name) } ?: false
            is RoutineAction.MediaVolume -> {
                audio ?: return false
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (remember && volumeBefore == null) volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * a.percent / 100f).toInt().coerceIn(0, max), 0)
                true
            }
            RoutineAction.DoNotDisturb -> {
                val nm = context.getSystemService<NotificationManager>() ?: return false
                if (!nm.isNotificationPolicyAccessGranted) return false
                if (remember && dndBefore == null) dndBefore = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                true
            }
            is RoutineAction.Wait -> {
                delay(a.seconds * 1_000L)
                true
            }
            RoutineAction.EndRide -> {
                session.requestEnd()
                delay(300)
                session.confirmEnd()
                true
            }
        }
    }

    /** Puts back what this ride's routines changed. */
    private suspend fun undo() = withContext(Dispatchers.Main) {
        volumeBefore?.let { v -> runCatching { context.getSystemService<AudioManager>()?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
        dndBefore?.let { f ->
            runCatching {
                val nm = context.getSystemService<NotificationManager>()
                if (nm?.isNotificationPolicyAccessGranted == true) nm.setInterruptionFilter(f)
            }
        }
        volumeBefore = null
        dndBefore = null
    }

    /** A headphone-style button press, which the music app that played last answers. */
    private fun mediaKey(audio: AudioManager?, code: Int): Boolean {
        audio ?: return false
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return true
    }

    /** Opens [intent]; from the background without the pop-up permission, as a notification to tap. */
    private fun open(intent: Intent, name: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (foreground() || Settings.canDrawOverlays(context)) {
            context.startActivity(intent)
            return true
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val tap = PendingIntent.getActivity(context, name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, RideTrackApp.RIDE_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Tap to open $name")
            .setContentText("Allow Keppo Moto's pop-up to open apps by itself")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTICE_ID + (name.hashCode() and 0xff), n)
        return true
    }

    private fun foreground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        const val MAPS = "com.google.android.apps.maps"
        private const val GAP_MILLIS = 1_200L
        private const val NOTICE_ID = 7_300
    }
}
