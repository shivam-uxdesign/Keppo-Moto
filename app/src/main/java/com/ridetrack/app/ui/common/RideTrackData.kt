package com.ridetrack.app.ui.common

import com.ridetrack.app.ui.components.GeoPoint
import com.ridetrack.telemetry.model.TelemetrySample

/** Route points from samples that actually had a GPS fix. */
fun List<TelemetrySample>.routePoints(): List<GeoPoint> = mapNotNull { s ->
    val lat = s.latitude
    val lon = s.longitude
    if (lat != null && lon != null) GeoPoint(lat, lon) else null
}

/** Index of the sample at or just before [timeMillis] (binary search); -1 when empty. */
fun List<TelemetrySample>.indexAt(timeMillis: Long): Int {
    if (isEmpty()) return -1
    if (timeMillis <= this[0].timeMillis) return 0
    var lo = 0
    var hi = size - 1
    if (timeMillis >= this[hi].timeMillis) return hi
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (this[mid].timeMillis <= timeMillis) lo = mid else hi = mid - 1
    }
    return lo
}

fun List<TelemetrySample>.sampleAt(timeMillis: Long): TelemetrySample? = indexAt(timeMillis).takeIf { it >= 0 }?.let { this[it] }

/** Nearest earlier sample that had a position (for the map marker). */
fun List<TelemetrySample>.positionAt(timeMillis: Long): GeoPoint? {
    for (i in indexAt(timeMillis) downTo 0) {
        val lat = this[i].latitude
        val lon = this[i].longitude
        if (lat != null && lon != null) return GeoPoint(lat, lon)
    }
    return null
}

/**
 * Position at [timeMillis] between GPS samples (straight-line blend of the fixes either side),
 * so a replay can move every frame instead of once per sample.
 */
fun List<TelemetrySample>.positionAtSmooth(timeMillis: Double): GeoPoint? {
    val i = indexAt(timeMillis.toLong())
    if (i < 0) return null
    val a = this[i]
    val b = getOrNull(i + 1)
    val aLat = a.latitude
    val aLon = a.longitude
    val bLat = b?.latitude
    val bLon = b?.longitude
    if (aLat == null || aLon == null || bLat == null || bLon == null || b.timeMillis <= a.timeMillis) return positionAt(timeMillis.toLong())
    val f = ((timeMillis - a.timeMillis) / (b.timeMillis - a.timeMillis)).coerceIn(0.0, 1.0)
    return GeoPoint(aLat + (bLat - aLat) * f, aLon + (bLon - aLon) * f)
}

/** [value] at [timeMillis], blended between the samples either side (null when not recorded). */
fun List<TelemetrySample>.valueAtSmooth(timeMillis: Double, value: (TelemetrySample) -> Double?): Double? {
    val i = indexAt(timeMillis.toLong())
    if (i < 0) return null
    val a = this[i]
    val va = value(a) ?: return null
    val b = getOrNull(i + 1) ?: return va
    val vb = value(b) ?: return va
    if (b.timeMillis <= a.timeMillis) return va
    val f = ((timeMillis - a.timeMillis) / (b.timeMillis - a.timeMillis)).coerceIn(0.0, 1.0)
    return va + (vb - va) * f
}

/** Moves [from] towards [to] (degrees) by [amount] (0..1), the short way round. */
fun easeAngle(from: Float, to: Float, amount: Float): Float {
    val diff = ((to - from) % 360f + 540f) % 360f - 180f
    return ((from + diff * amount) % 360f + 360f) % 360f
}
