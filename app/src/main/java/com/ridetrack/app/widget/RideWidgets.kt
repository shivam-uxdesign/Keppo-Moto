package com.ridetrack.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.getSystemService
import com.ridetrack.app.AppContainer
import com.ridetrack.app.MainActivity
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones
import com.ridetrack.app.ride.QuickStart
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.common.BikeColors
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.theme.Accent
import com.ridetrack.telemetry.model.DataSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** What the widgets show. Pure data, so the wording is tested without a home screen. */
data class WidgetState(
    val riding: Boolean,
    val paused: Boolean,
    /** Ride time so far, for the chronometer. */
    val elapsedMillis: Long,
    val distanceText: String,
    val bikeName: String?,
    val accent: Int,
    val readiness: String,
    val lastRide: String?,
) {
    companion object {
        fun readiness(mic: String?, gpsOn: Boolean, locationAllowed: Boolean): String = listOf(
            mic?.let { "$it connected" } ?: "No external mic",
            when {
                !locationAllowed -> "Location not allowed"
                gpsOn -> "GPS on"
                else -> "GPS off"
            },
        ).joinToString(" · ")

        fun distance(meters: Double): String = String.format(Locale.US, "%.1f %s", Units.distance(meters), Units.distanceUnit)
    }
}

/**
 * The home-screen widgets (small: Start ride; large: also readiness and the last ride; both show
 * the ride while recording). Plain RemoteViews: no extra library, and they update when the ride
 * starts, pauses or ends, and every 15 s while riding.
 */
object RideWidgets {
    private const val ACTION_PAUSE = "com.keppo.moto.widget.PAUSE"
    private const val ACTION_RESUME = "com.keppo.moto.widget.RESUME"

    /** Keeps the widgets in step with the ride for as long as the app runs. */
    fun watch(context: Context, c: AppContainer, scope: CoroutineScope) {
        scope.launch {
            combine(c.session.state, c.session.active, c.session.manuallyPaused, c.settings.settings, c.bikes.observeBikes()) { _, _, _, _, _ -> Unit }
                .collect { refresh(context, c) }
        }
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (c.session.state.value.isActive) refresh(context, c)
            }
        }
    }

    private fun ids(context: Context): Pair<IntArray, IntArray> {
        val m = AppWidgetManager.getInstance(context)
        return m.getAppWidgetIds(ComponentName(context, RideWidgetSmall::class.java)) to
            m.getAppWidgetIds(ComponentName(context, RideWidgetLarge::class.java))
    }

    suspend fun refresh(context: Context, c: AppContainer) {
        val (small, large) = runCatching { ids(context) }.getOrNull() ?: return
        if (small.isEmpty() && large.isEmpty()) return
        val state = snapshot(context, c)
        val m = AppWidgetManager.getInstance(context)
        small.forEach { m.updateAppWidget(it, small(context, state)) }
        large.forEach { m.updateAppWidget(it, large(context, state)) }
    }

    private suspend fun snapshot(context: Context, c: AppContainer): WidgetState {
        val s = c.settings.settings.first()
        val garage = c.bikes.observeBikes().first()
        val active = c.session.active.value
        val bike = garage.firstOrNull { it.id == active?.bikeId } ?: QuickStart.bike(garage, s.selectedBikeId)
        val accent = bike?.let { BikeColors.of(it, garage).let(BikeColors::argb) } ?: Accent.TEAL
        val frame = c.session.frame.value
        val mic = runCatching { Microphones.available(context).firstOrNull { it.type != MicType.PHONE }?.label }.getOrNull()
        val gpsOn = runCatching { context.getSystemService<LocationManager>()?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)
        val last = kotlinx.coroutines.withContext(Dispatchers.IO) {
            c.rides.observeCompleted().first().filter { it.source != DataSourceKind.DEMO }.maxByOrNull { it.startTimeMillis }
        }
        return WidgetState(
            riding = c.session.state.value.isActive,
            paused = c.session.manuallyPaused.value,
            elapsedMillis = frame?.elapsedMillis ?: active?.let { System.currentTimeMillis() - it.startWallMillis } ?: 0L,
            distanceText = WidgetState.distance(frame?.stats?.distanceM ?: 0.0),
            bikeName = (active?.bikeName ?: bike?.let { "${it.make} ${it.model}".trim() })?.takeIf { it.isNotBlank() },
            accent = accent,
            readiness = WidgetState.readiness(mic, gpsOn, Permissions.hasFineLocation(context)),
            lastRide = last?.let { "Last ride: ${WidgetState.distance(it.stats.distanceM)} · ${Format.duration(it.durationMillis)} · ${Format.rideDate(it.startTimeMillis)}" },
        )
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 40, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun end(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 42, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_END_RIDE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, RideWidgetActions::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun RemoteViews.chronometer(state: WidgetState) {
        setChronometer(R.id.widget_time, SystemClock.elapsedRealtime() - state.elapsedMillis, null, state.riding && !state.paused)
    }

    private fun small(context: Context, s: WidgetState) = RemoteViews(context.packageName, R.layout.widget_small).apply {
        setInt(R.id.widget_start_bg, "setColorFilter", s.accent)
        setInt(R.id.widget_start_icon, "setColorFilter", Accent.onAccent(s.accent))
        if (s.riding) {
            setViewVisibility(R.id.widget_title, View.GONE)
            setViewVisibility(R.id.widget_time, View.VISIBLE)
            chronometer(s)
            setTextViewText(R.id.widget_sub, if (s.paused) "Paused · ${s.distanceText}" else s.distanceText)
            setImageViewResource(R.id.widget_start_icon, R.drawable.ic_tile_ride)
            setOnClickPendingIntent(R.id.widget_root, open(context))
            setOnClickPendingIntent(R.id.widget_start, open(context))
        } else {
            setViewVisibility(R.id.widget_title, View.VISIBLE)
            setViewVisibility(R.id.widget_time, View.GONE)
            setTextViewText(R.id.widget_sub, s.bikeName ?: "Add a bike in the app")
            setImageViewResource(R.id.widget_start_icon, R.drawable.ic_widget_play)
            setOnClickPendingIntent(R.id.widget_root, QuickStart.pendingIntent(context))
            setOnClickPendingIntent(R.id.widget_start, QuickStart.pendingIntent(context))
        }
    }

    private fun large(context: Context, s: WidgetState) = RemoteViews(context.packageName, R.layout.widget_large).apply {
        setTextViewText(R.id.widget_bike, s.bikeName ?: "Keppo Moto")
        setTextViewText(R.id.widget_ready, if (s.riding) (if (s.paused) "Paused" else "Recording") else s.readiness)
        setViewVisibility(R.id.widget_start, if (s.riding) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.widget_riding, if (s.riding) View.VISIBLE else View.GONE)
        setInt(R.id.widget_start_bg, "setColorFilter", s.accent)
        setTextColor(R.id.widget_start_label, Accent.onAccent(s.accent))
        setOnClickPendingIntent(R.id.widget_start, QuickStart.pendingIntent(context))
        setOnClickPendingIntent(R.id.widget_root, open(context))
        if (s.riding) {
            chronometer(s)
            setTextViewText(R.id.widget_distance, s.distanceText)
            setImageViewResource(R.id.widget_pause_icon, if (s.paused) R.drawable.ic_widget_play else R.drawable.ic_widget_pause)
            setContentDescription(R.id.widget_pause, context.getString(if (s.paused) R.string.widget_resume else R.string.widget_pause))
            setOnClickPendingIntent(R.id.widget_pause, action(context, if (s.paused) ACTION_RESUME else ACTION_PAUSE, 43))
            setOnClickPendingIntent(R.id.widget_end, end(context))
        }
        setViewVisibility(R.id.widget_last, if (s.lastRide == null || s.riding) View.GONE else View.VISIBLE)
        setTextViewText(R.id.widget_last, s.lastRide.orEmpty())
    }

    internal fun handle(context: Context, action: String?) {
        val c = (context.applicationContext as RideTrackApp).container
        when (action) {
            ACTION_PAUSE -> c.session.pause()
            ACTION_RESUME -> c.session.resume()
        }
    }
}

/** Small widget (2×1). */
class RideWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)
}

/** Large widget (4×2). */
class RideWidgetLarge : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)
}

private fun AppWidgetProvider.refreshAsync(context: Context) {
    val c = (context.applicationContext as RideTrackApp).container
    val pending = goAsync()
    c.appScope.launch {
        try {
            RideWidgets.refresh(context.applicationContext, c)
        } finally {
            pending.finish()
        }
    }
}

/** The widget's Pause and Resume. */
class RideWidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RideWidgets.handle(context, intent.action)
}
