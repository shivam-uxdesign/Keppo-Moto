package com.ridetrack.app.shots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ridetrack.app.widget.BikeCard
import com.ridetrack.app.widget.BikeCareWidget
import com.ridetrack.app.widget.BikeWidget
import com.ridetrack.app.widget.CalendarWidget
import com.ridetrack.app.widget.DaylightWidget
import com.ridetrack.app.widget.FilmToday
import com.ridetrack.app.widget.FilmTodayWidget
import com.ridetrack.app.widget.HomeWidgets
import com.ridetrack.app.widget.LastRideWidget
import com.ridetrack.app.widget.MomentsWidget
import com.ridetrack.app.widget.Readiness
import com.ridetrack.app.widget.RecordCard
import com.ridetrack.app.widget.RecordsWidget
import com.ridetrack.app.widget.RideCard
import com.ridetrack.app.widget.Snapshot
import com.ridetrack.app.widget.WidgetArt
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Draws every home-screen widget at each size with the prototype's sample data (build/shots/widgets). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class WidgetShots {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))

    @Test fun all() {
        val s = WidgetSamples.snapshot(route())
        val art = WidgetArt(ctx)
        listOf(
            Triple(BikeWidget::class.java, "bike", listOf(4 to 2, 2 to 2, 2 to 1, 1 to 1, 4 to 1)),
            Triple(FilmTodayWidget::class.java, "film", listOf(4 to 2)),
            Triple(DaylightWidget::class.java, "daylight", listOf(2 to 1)),
            Triple(BikeCareWidget::class.java, "care", listOf(2 to 1)),
            Triple(LastRideWidget::class.java, "ride", listOf(4 to 2, 2 to 2, 2 to 1, 4 to 1)),
            Triple(MomentsWidget::class.java, "moments", listOf(2 to 1)),
            Triple(RecordsWidget::class.java, "records", listOf(4 to 2, 2 to 2, 2 to 1, 1 to 1)),
            Triple(CalendarWidget::class.java, "calendar", listOf(4 to 3, 4 to 2, 2 to 2, 2 to 1)),
        ).forEach { (cls, name, sizes) ->
            sizes.forEach { (c, r) ->
                val (w, h) = WidgetSamples.dp(c, r)
                val rv = HomeWidgets.render(ctx, art, s, cls, 1, w.toFloat(), h.toFloat())
                save(rv.apply(ctx, FrameLayout(ctx)), w, h, "build/shots/widgets/$name-${c}x$r.png")
            }
        }
    }

    /** Other phones' cells: wider, shorter and taller than the prototype's, so pictures must fit, not stretch. */
    @Test fun otherSizes() {
        val s = WidgetSamples.snapshot(route())
        val art = WidgetArt(ctx)
        listOf(
            Triple(BikeWidget::class.java, "bike", 410 to 170), Triple(BikeWidget::class.java, "bike", 200 to 240),
            Triple(BikeWidget::class.java, "bike", 100 to 110), Triple(LastRideWidget::class.java, "ride", 410 to 170),
            Triple(LastRideWidget::class.java, "ride", 200 to 240), Triple(RecordsWidget::class.java, "records", 410 to 170),
            Triple(CalendarWidget::class.java, "calendar", 200 to 240), Triple(CalendarWidget::class.java, "calendar", 410 to 330),
        ).forEach { (cls, name, size) ->
            val (w, h) = size
            save(HomeWidgets.render(ctx, art, s, cls, 3, w.toFloat(), h.toFloat()).apply(ctx, FrameLayout(ctx)), w, h, "build/shots/widgets/sizes/$name-${w}x$h.png")
        }
    }

    /**
     * The widget picker's thumbnails: each widget at the size it's added at, on clear ground,
     * at 2× (build/shots/widgets/preview; copied into res/drawable-nodpi as hw_preview_*).
     */
    @Test fun previews() {
        val s = WidgetSamples.snapshot(route())
        val art = WidgetArt(ctx)
        listOf(
            Triple(BikeWidget::class.java, "bike", 4 to 2), Triple(FilmTodayWidget::class.java, "film", 4 to 2),
            Triple(DaylightWidget::class.java, "daylight", 2 to 1), Triple(BikeCareWidget::class.java, "care", 2 to 1),
            Triple(LastRideWidget::class.java, "last_ride", 4 to 2), Triple(MomentsWidget::class.java, "moments", 2 to 1),
            Triple(RecordsWidget::class.java, "records", 2 to 2), Triple(CalendarWidget::class.java, "calendar", 4 to 3),
        ).forEach { (cls, name, size) ->
            val (w, h) = WidgetSamples.dp(size.first, size.second)
            val rv = HomeWidgets.render(ctx, art, s, cls, 2, w.toFloat(), h.toFloat())
            save(rv.apply(ctx, FrameLayout(ctx)), w, h, "build/shots/widgets/preview/hw_preview_$name.png", pad = 0, ground = 0, scale = 2f / 3f)
        }
    }

    /** A made-up route picture, as the app draws one after a ride. */
    private fun route(): File {
        val d = ctx.resources.displayMetrics.density
        val b = Bitmap.createBitmap((180 * d).toInt(), (180 * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF101418.toInt())
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2228.toInt(); strokeWidth = 2 * d }
        for (k in 1..5) { c.drawLine(k * 30 * d, 0f, k * 30 * d - 40 * d, b.height.toFloat(), grid); c.drawLine(0f, k * 32 * d, b.width.toFloat(), k * 32 * d + 20 * d, grid) }
        val path = Path().apply {
            moveTo(30 * d, 150 * d); cubicTo(40 * d, 110 * d, 70 * d, 120 * d, 80 * d, 90 * d)
            cubicTo(90 * d, 60 * d, 130 * d, 70 * d, 140 * d, 45 * d); cubicTo(146 * d, 30 * d, 120 * d, 22 * d, 110 * d, 30 * d)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        line.shader = LinearGradient(30 * d, 150 * d, 140 * d, 30 * d, intArrayOf(0xFF69C8CB.toInt(), 0xFFFBBF24.toInt(), 0xFFFB7185.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, line)
        val f = File(ctx.cacheDir, "route.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun save(v: View, wDp: Int, hDp: Int, path: String, pad: Int = 12, ground: Int = 0xFF0F1316.toInt(), scale: Float = 1f) {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val p = (pad * d).toInt()
        val b = Bitmap.createBitmap(w + 2 * p, h + 2 * p, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(ground)
        c.translate(p.toFloat(), p.toFloat())
        v.draw(c)
        val out = if (scale == 1f) b else Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
        File(path).apply { parentFile?.mkdirs() }.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** The prototype's riders, bikes and rides. */
internal object WidgetSamples {
    /** A cell's size in dp, as the prototype draws it (a 4×2 is 360×200). */
    fun dp(cols: Int, rows: Int): Pair<Int, Int> = (when (cols) { 1 -> 84; 2 -> 176; else -> 360 }) to (when (rows) { 1 -> 96; 2 -> 200; else -> 304 })

    fun snapshot(route: File?): Snapshot {
        val today = LocalDate.of(2026, 10, 20)
        val zone = ZoneId.of("Asia/Kolkata")
        val days = HashMap<LocalDate, Double>()
        val pattern = doubleArrayOf(24.0, 0.0, 62.0, 0.0, 0.0, 118.0, 212.0, 0.0, 36.0, 0.0, 0.0, 74.0, 0.0, 15.0)
        var d = LocalDate.of(2026, 1, 3)
        var k = 0
        while (!d.isAfter(today)) { pattern[k % pattern.size].takeIf { it > 0 }?.let { days[d] = it }; d = d.plusDays(1); k++ }
        days[today] = 45.4; days[today.minusDays(1)] = 62.0; days[today.minusDays(2)] = 248.0
        val noon = today.atTime(16, 40).atZone(zone).toInstant().toEpochMilli()
        return Snapshot(
            bikes = listOf(
                BikeCard("a", "Pulsar NS 200", "12,480 km", 0xFF69C8CB.toInt(), null, "~140 km in the tank", "Chain lube · in 120 km", "lube in 120 km", 1),
                BikeCard("b", "Himalayan 450", "3,210 km", 0xFFFBBF24.toInt(), null, "~260 km in the tank", "First service · in 9 days", "service in 9 days", 0),
                BikeCard("c", "Classic 350", "28,940 km", 0xFFA5A1FF.toInt(), null, "~90 km in the tank", "Oil change · due now", "oil change due", 2),
            ),
            bikeIx = 0,
            ready = Readiness.ALL_SET,
            rides = listOf(
                RideCard("r1", "TUE", "Tue 20 Oct", "Dwarka Expressway", "45.4", "1:13", "121", "38°", true, 7),
                RideCard("r2", "MON", "Mon 19 Oct", "Sohna hills", "62.0", "1:48", "104", "33°", false, 4),
                RideCard("r3", "SUN", "Sun 18 Oct", "Delhi to Nahan", "212", "5:02", "112", "31°", true, 12),
            ),
            routes = listOf(route, route, route),
            newestEndMillis = noon - 3_600_000,
            thumbs = emptyList(),
            records = listOf(
                RecordCard("TOP SPEED", "TOP", "121", "km/h", "20 Oct · Dwarka Expressway", "r1", true),
                RecordCard("LEAN LEFT", "LEAN L", "38°", "left", "20 Oct · Sector 79 flyover", "r1", false, 'L'),
                RecordCard("LEAN RIGHT", "LEAN R", "33°", "right", "19 Oct · Sohna hills", "r2", false, 'R'),
                RecordCard("LONGEST RIDE", "LONGEST", "212", "km", "18 Oct · Delhi to Nahan", "r3", false),
                RecordCard("BEST DAY", "BEST DAY", "248", "km", "18 Oct · 2 rides", "r3", false),
                RecordCard("BEST WEEK", "WEEK", "512", "km", "Week of 13 Oct · 6 rides", null, false),
            ),
            days = days,
            today = today,
            sun = today.atTime(6, 27).atZone(zone).toInstant().toEpochMilli() to today.atTime(17, 46).atZone(zone).toInstant().toEpochMilli(),
            now = noon,
            film = FilmToday("reel", "Monsoon run to Sohna", 2, 6, listOf(
                "On the bike · chest mount" to "#3 Rolling out of the gate · #4 First corner",
                "Off the bike · tripod" to "#1 Helmet on · #6 Parked at the view",
            )),
            filmRiding = listOf(true, false),
        )
    }
}
