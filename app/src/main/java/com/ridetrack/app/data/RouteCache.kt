package com.ridetrack.app.data

import com.ridetrack.app.ui.common.routePoints
import com.ridetrack.app.ui.components.GeoPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified routes of completed rides, loaded once per process. Used where many routes
 * are drawn at once (list thumbnails, the all-rides map, share graphics); ride detail
 * still loads the full track.
 */
class RouteCache(private val rides: RideRepository) {
    private val cache = ConcurrentHashMap<String, List<GeoPoint>>()
    private val mutex = Mutex()

    suspend fun route(rideId: String): List<GeoPoint> {
        cache[rideId]?.let { return it }
        return mutex.withLock {
            cache[rideId] ?: simplify(rides.track(rideId).samples.routePoints()).also { cache[rideId] = it }
        }
    }

    fun forget(rideId: String) {
        cache.remove(rideId)
    }

    companion object {
        /** Evenly thinned to at most [max] points, always keeping the last one. */
        fun simplify(route: List<GeoPoint>, max: Int = 160): List<GeoPoint> {
            if (route.size <= max) return route
            val step = route.size.toDouble() / (max - 1)
            return List(max - 1) { route[(it * step).toInt()] } + route.last()
        }
    }
}
