package com.ridetrack.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.ridetrack.app.data.MapStyle
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.data.db.RideTrackDatabase
import com.ridetrack.app.data.toModel
import com.ridetrack.app.ui.components.mapAttribution
import com.ridetrack.app.ui.components.styleBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * A ride's route on a dark map: a MapLibre snapshot fitted to the route, with the heat line
 * drawn over it. Used for the ride cards and Keppo Journal's `route.png`.
 *
 * Needs the network for the map tiles. Offline, [mapImage] returns null and callers fall back
 * to the plain route line; the next call once online makes the map version, which then sticks.
 */
class RouteImages(private val context: Context, private val db: RideTrackDatabase) {
    private val dir = File(context.cacheDir, "route-maps")
    private val lock = Mutex()
    /** A snapshot that failed while online isn't retried for a while. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The cached map picture, without trying to make one. */
    fun cached(ride: RideEntity, sizePx: Int): File? = file(ride.id, sizePx).takeIf { fresh(it, ride) }

    suspend fun mapImage(rideId: String, sizePx: Int): File? = db.rideDao().get(rideId)?.let { mapImage(it, sizePx) }

    /** The map picture at [sizePx] square, made now if needed; null when it can't be (offline). */
    suspend fun mapImage(ride: RideEntity, sizePx: Int): File? {
        cached(ride, sizePx)?.let { return it }
        val key = "${ride.id}/$sizePx"
        if (!online() || System.currentTimeMillis() - (failedAt[key] ?: 0) < RETRY_MILLIS) return null
        return lock.withLock {
            cached(ride, sizePx)?.let { return@withLock it }
            val data = ShareCardData.from(ride.toModel(), null, db.rideDao().samples(ride.id).map { it.toModel() })
            if (data.route.size < 2) return@withLock null
            val made = runCatching { render(data, sizePx) }.onFailure { Log.w(TAG, "Map snapshot failed", it) }.getOrNull()
            if (made == null) {
                failedAt[key] = System.currentTimeMillis()
                return@withLock null
            }
            withContext(Dispatchers.IO) {
                val f = file(ride.id, sizePx)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "${f.name}.part")
                tmp.outputStream().use { made.compress(Bitmap.CompressFormat.PNG, 100, it) }
                made.recycle()
                tmp.renameTo(f)
                failedAt.remove(key)
                f
            }
        }
    }

    private suspend fun render(data: ShareCardData, sizePx: Int): Bitmap? {
        val shot = snapshot(data.route, sizePx) ?: return null
        val (base, project) = shot
        ShareCardRenderer(context).renderRouteOn(base, data, project)
        if (sizePx >= ATTRIBUTION_MIN_PX) attribution(base)
        return base
    }

    /** The dark map fitted to [route], as a mutable bitmap and a lat/lon → pixel projection. */
    private suspend fun snapshot(route: List<SharePoint>, sizePx: Int): Pair<Bitmap, (SharePoint) -> PointF>? =
        withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val logical = (sizePx / PIXEL_RATIO).toInt()
                    val options = MapSnapshotter.Options(logical, logical)
                        .withStyleBuilder(styleBuilder(MapStyle.DARK))
                        .withRegion(paddedBounds(route))
                        .withPixelRatio(PIXEL_RATIO)
                        .withLogo(false)
                    val snapshotter = MapSnapshotter(context, options)
                    cont.invokeOnCancellation { snapshotter.cancel() }
                    snapshotter.start(
                        { snap ->
                            val bmp = snap.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            // pixelForLatLng is in bitmap pixels (it applies the pixel ratio).
                            val project: (SharePoint) -> PointF = { p -> snap.pixelForLatLng(LatLng(p.latitude, p.longitude)) }
                            // Project now, while the snapshot is alive.
                            val points = route.associateWith(project)
                            if (cont.isActive) cont.resume(bmp to { p: SharePoint -> points[p] ?: project(p) })
                        },
                        { error ->
                            Log.w(TAG, "Map snapshot error: $error")
                            if (cont.isActive) cont.resume(null)
                        },
                    )
                }
            }
        }

    private fun attribution(bmp: Bitmap) {
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = bmp.width * 0.018f
            textAlign = Paint.Align.RIGHT
        }
        val m = bmp.width * 0.02f
        c.drawText(mapAttribution(), bmp.width - m, bmp.height - m, paint)
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun file(rideId: String, sizePx: Int) = File(File(dir, rideId), "$sizePx.png")
    private fun fresh(f: File, ride: RideEntity) = f.isFile && f.lastModified() >= ride.lastUpdateMillis

    companion object {
        private const val TAG = "RouteImages"
        private const val PIXEL_RATIO = 2f
        private const val SNAPSHOT_TIMEOUT_MILLIS = 20_000L
        private const val RETRY_MILLIS = 10 * 60_000L
        private const val ATTRIBUTION_MIN_PX = 600

        /** Room around the route, as a share of its span on each side. */
        private const val PAD = 0.18

        /** The route's bounds with [PAD] around it, never smaller than a few hundred metres. */
        internal fun paddedBounds(route: List<SharePoint>): LatLngBounds {
            val b = paddedBox(route)
            return LatLngBounds.from(b[0], b[1], b[2], b[3])
        }

        /** north, east, south, west. Pure, so it is unit-tested. */
        fun paddedBox(route: List<SharePoint>, pad: Double = PAD, minSpan: Double = 0.004): DoubleArray {
            val minLat = route.minOf { it.latitude }
            val maxLat = route.maxOf { it.latitude }
            val minLon = route.minOf { it.longitude }
            val maxLon = route.maxOf { it.longitude }
            val latSpan = maxOf(maxLat - minLat, minSpan)
            val lonSpan = maxOf(maxLon - minLon, minSpan)
            val cLat = (minLat + maxLat) / 2
            val cLon = (minLon + maxLon) / 2
            val halfLat = latSpan * (0.5 + pad)
            val halfLon = lonSpan * (0.5 + pad)
            return doubleArrayOf(
                (cLat + halfLat).coerceAtMost(85.0),
                (cLon + halfLon).coerceAtMost(180.0),
                (cLat - halfLat).coerceAtLeast(-85.0),
                (cLon - halfLon).coerceAtLeast(-180.0),
            )
        }
    }
}
