package com.ridetrack.app

import android.content.Context
import com.ridetrack.app.data.BikeRepository
import com.ridetrack.app.data.RideRepository
import com.ridetrack.app.data.RouteCache
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.data.db.RideTrackDatabase
import com.ridetrack.app.hud.HudController
import com.ridetrack.app.moments.MomentRepository
import com.ridetrack.app.moments.MomentsHub
import com.ridetrack.app.ride.RideSessionManager
import com.ridetrack.app.safety.CrashAlerts
import com.ridetrack.app.sensors.BatteryMonitor
import com.ridetrack.app.sensors.PhoneTelemetrySource
import com.ridetrack.app.sensors.SensorInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; one instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = RideTrackDatabase.create(appContext)
    val bikes = BikeRepository(database.bikeDao())
    val rides = RideRepository(database.rideDao())
    val routes = RouteCache(rides)
    val moments = MomentRepository(appContext, database.momentDao())
    val momentsHub = MomentsHub()
    val settings = SettingsRepository(appContext)
    val sensorInventory = SensorInventory(appContext)
    val battery = BatteryMonitor(appContext)

    fun phoneSource(includeGps: Boolean = true) = PhoneTelemetrySource(appContext, sensorInventory, includeGps)

    val session = RideSessionManager(
        context = appContext,
        rides = rides,
        bikes = bikes,
        momentsHub = momentsHub,
        settings = settings,
        phoneSource = { phoneSource() },
        scope = appScope,
    )

    val hud = HudController(appContext, session, settings, momentsHub)
    val crashAlerts = CrashAlerts(appContext, settings, session, battery, appScope)
}
