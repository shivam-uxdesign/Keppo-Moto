package com.ridetrack.app.ride

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import com.ridetrack.app.moments.MomentRecorder
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ridetrack.app.MainActivity
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.TelemetryFrame
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and GPS/IMU collection) alive while a ride
 * is recorded with the screen off or another app in front. The recording itself lives in
 * [RideSessionManager]; this service only holds the foreground state and a wake lock.
 */
class RideRecordingService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var recorder: MomentRecorder? = null
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopGracefully()
            return START_NOT_STICKY
        }
        val session = (application as RideTrackApp).container.session
        if (!started) {
            started = true
            // Always satisfy the startForegroundService() contract before anything else.
            if (!enterForeground()) return START_NOT_STICKY
            if (!session.state.value.isActive) {
                // Restarted without a live session (e.g. after process death): nothing to do.
                shutdown()
                return START_NOT_STICKY
            }
            acquireWakeLock()
            observe(session)
            startMoments(session)
        }
        return START_NOT_STICKY
    }

    /** Filming Moments needs camera/microphone foreground types, declared only when granted. */
    private fun startMoments(session: RideSessionManager) {
        val active = session.active.value ?: return
        val settings = active.moments ?: return
        val c = (application as RideTrackApp).container
        val r = MomentRecorder(this, this, c.moments, c.momentsHub, settings, active.landscapeMount, c.appScope)
        recorder = r
        r.start()
        lifecycleScope.launch {
            session.state.collectLatest { st -> r.setRidePaused(st is RideState.Paused) }
        }
    }

    /** Lets Moments write its last clips (bounded) before leaving the foreground. */
    private fun stopGracefully() {
        if (stopping) return
        stopping = true
        val r = recorder
        recorder = null
        if (r == null) {
            shutdown()
            return
        }
        (application as RideTrackApp).container.appScope.launch {
            runCatching { r.finish() }
            launch(kotlinx.coroutines.Dispatchers.Main) { shutdown() }
        }
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = 0
        if (Permissions.hasFineLocation(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val moments = (application as RideTrackApp).container.session.active.value?.moments != null
        if (moments && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground(): Boolean = try {
        val type = foregroundTypes()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type)
        true
    } catch (e: Exception) {
        // E.g. location permission revoked; recording continues while the app is visible.
        Log.e(TAG, "Could not enter foreground", e)
        shutdown()
        false
    }

    // Notification permission is checked via Permissions.hasNotifications before notify().
    @SuppressLint("MissingPermission")
    private fun observe(session: RideSessionManager) {
        lifecycleScope.launch {
            session.state.collectLatest { if (!it.isActive && it !is RideState.Saving) stopGracefully() }
        }
        lifecycleScope.launch {
            session.frame.filterNotNull().sample(NOTIFICATION_UPDATE_MILLIS).collectLatest { frame ->
                if (Permissions.hasNotifications(this@RideRecordingService)) {
                    try {
                        NotificationManagerCompat.from(this@RideRecordingService).notify(NOTIFICATION_ID, buildNotification(frame))
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Notification update denied", e)
                    }
                }
            }
        }
    }

    private fun buildNotification(frame: TelemetryFrame?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = frame?.let {
            "${Format.distance(it.stats.distanceM)} · ${Format.clock(it.elapsedMillis)}"
        } ?: "Starting…"
        return NotificationCompat.Builder(this, RideTrackApp.RIDE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(if (frame?.isStopped == true) "Ride in progress · stopped" else "Ride in progress")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    private fun acquireWakeLock() {
        // Keeps the IMU delivering while the screen is off; released when the ride ends.
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideTrack:recording")
            ?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MILLIS)
            }
    }

    private fun shutdown() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RideService"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_UPDATE_MILLIS = 5_000L
        private const val MAX_WAKE_LOCK_MILLIS = 12 * 60 * 60 * 1000L
        private const val ACTION_STOP = "com.ridetrack.app.STOP_RECORDING"

        fun start(context: Context) {
            // Each foreground type needs its permission: location for GPS, camera for Moments.
            val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!Permissions.hasFineLocation(context) && !camera) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, RideRecordingService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, RideRecordingService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Service not running / app in background: nothing to stop.
                Log.w(TAG, "Stop request ignored", e)
            }
        }
    }
}
