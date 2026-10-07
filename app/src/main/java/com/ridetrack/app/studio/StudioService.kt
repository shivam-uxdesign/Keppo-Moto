package com.ridetrack.app.studio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.ridetrack.app.RideTrackApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app running while Studio makes Reels, so switching apps or swiping it away doesn't
 * stop the making (Android pauses apps in the background otherwise). Shows the maker's
 * notification and stops itself when nothing is being made.
 */
class StudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val maker = (application as RideTrackApp).container.reelMaker
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ReelMaker.NOTIFICATION, maker.ongoing(), type)
            else startForeground(ReelMaker.NOTIFICATION, maker.ongoing())
        }
        if (started.isFailure) {
            (application as RideTrackApp).container.errors.warn("Studio making", "Couldn't keep making in the background: it stops if you leave the app", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch?.isActive != true) {
            watch = scope.launch {
                maker.state.collectLatest { s -> if (s.idle) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    /** Android ends media work after hours; whatever is left is picked up the next time the app starts. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts keeping the app running (when the app may: from the screen, or right after a ride). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, StudioService::class.java)) }
                .onFailure { e ->
                    (context.applicationContext as? RideTrackApp)?.container?.errors?.warn("Studio making", "Couldn't keep making in the background: it stops if you leave the app", e)
                }
        }
    }
}
