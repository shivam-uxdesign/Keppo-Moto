package com.ridetrack.app.ui.rides

import java.io.File
import com.ridetrack.app.moments.RideMoments
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.ui.components.GeoPoint
import kotlinx.coroutines.launch
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.Ride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class DateFilter(val label: String) { ALL("Any time"), WEEK("7 days"), MONTH("30 days"), YEAR("This year") }
enum class DistanceFilter(val label: String, val range: ClosedFloatingPointRange<Double>) {
    ANY("Any", 0.0..Double.MAX_VALUE),
    SHORT("< 10 km", 0.0..9_999.999),
    MEDIUM("10–50 km", 10_000.0..50_000.0),
    LONG("> 50 km", 50_000.001..Double.MAX_VALUE),
}
enum class DurationFilter(val label: String, val range: LongRange) {
    ANY("Any", 0L..Long.MAX_VALUE),
    SHORT("< 30 min", 0L until 30 * 60_000L),
    MEDIUM("30–90 min", 30 * 60_000L..90 * 60_000L),
    LONG("> 90 min", 90 * 60_000L + 1..Long.MAX_VALUE),
}

data class RideFilter(
    val date: DateFilter = DateFilter.ALL,
    val bikeId: String? = null,
    val distance: DistanceFilter = DistanceFilter.ANY,
    val duration: DurationFilter = DurationFilter.ANY,
) {
    val activeCount: Int
        get() = listOf(date != DateFilter.ALL, bikeId != null, distance != DistanceFilter.ANY, duration != DurationFilter.ANY).count { it }
}

enum class RideGroup(val label: String) { TODAY("Today"), YESTERDAY("Yesterday"), THIS_WEEK("This week"), EARLIER("Earlier") }

data class RidesUiState(
    val loading: Boolean = true,
    val totalRides: Int = 0,
    val groups: List<Pair<RideGroup, List<Ride>>> = emptyList(),
    val bikes: List<Bike> = emptyList(),
    val filter: RideFilter = RideFilter(),
    val routes: Map<String, List<GeoPoint>> = emptyMap(),
    /** Route-on-map pictures, once made (online). */
    val maps: Map<String, File> = emptyMap(),
    val moments: Map<String, RideMoments> = emptyMap(),
)

class RidesViewModel(private val c: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(RideFilter())
    private val routes = MutableStateFlow<Map<String, List<GeoPoint>>>(emptyMap())
    private val maps = MutableStateFlow<Map<String, File>>(emptyMap())
    private val requested = mutableSetOf<String>()

    /** Loads route sketches newest-first, then the map pictures, in the background. */
    private fun requestRoutes(ids: List<String>) {
        val missing = ids.filter { requested.add(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            missing.forEach { id -> routes.update { it + (id to c.routes.route(id)) } }
            missing.forEach { id -> c.routeImages.mapImage(id, CARD_MAP_PX)?.let { f -> maps.update { it + (id to f) } } }
        }
    }

    private val pictures = combine(routes, maps, c.moments.observeCards()) { r, m, mo -> Triple(r, m, mo) }

    val state: StateFlow<RidesUiState> = combine(c.rides.observeCompleted(), c.bikes.observeBikes(), filter, pictures) { rides, bikes, f, (r, m, mo) ->
        requestRoutes(rides.map { it.id })
        RidesUiState(
            loading = false,
            totalRides = rides.size,
            groups = group(rides.filter { matches(it, f) }),
            bikes = bikes,
            filter = f,
            routes = r,
            maps = m,
            moments = mo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RidesUiState())

    fun setFilter(f: RideFilter) = filter.update { f }
    fun clearFilters() = filter.update { RideFilter() }

    private fun matches(ride: Ride, f: RideFilter, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val day = Instant.ofEpochMilli(ride.startTimeMillis).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val dateOk = when (f.date) {
            DateFilter.ALL -> true
            DateFilter.WEEK -> !day.isBefore(today.minusDays(6))
            DateFilter.MONTH -> !day.isBefore(today.minusDays(29))
            DateFilter.YEAR -> day.year == today.year
        }
        return dateOk &&
            (f.bikeId == null || ride.bikeId == f.bikeId) &&
            ride.stats.distanceM in f.distance.range &&
            (ride.durationMillis ?: 0L) in f.duration.range
    }

    private fun group(rides: List<Ride>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<RideGroup, List<Ride>>> {
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return rides.groupBy { ride ->
            val d = Instant.ofEpochMilli(ride.startTimeMillis).atZone(zone).toLocalDate()
            when {
                d == today -> RideGroup.TODAY
                d == today.minusDays(1) -> RideGroup.YESTERDAY
                !d.isBefore(weekStart) -> RideGroup.THIS_WEEK
                else -> RideGroup.EARLIER
            }
        }.toList().sortedBy { it.first.ordinal }
    }

    private companion object {
        const val CARD_MAP_PX = 320
    }
}
