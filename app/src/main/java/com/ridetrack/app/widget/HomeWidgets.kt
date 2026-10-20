package com.ridetrack.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.getSystemService
import com.ridetrack.app.AppContainer
import com.ridetrack.app.MainActivity
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.fuel.Fuel
import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones
import com.ridetrack.app.ride.QuickStart
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.studio.storyboard.Placeholders
import com.ridetrack.app.studio.storyboard.TemplateBuilder
import com.ridetrack.app.studio.storyboard.ToFilm
import com.ridetrack.app.ui.common.BikeColors
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.home.BikeCare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Which design a widget shows now, so a tap can animate just its moving parts. */
internal enum class Shown(val layout: Int, val photo: Boolean, val text: Boolean, val dots: Boolean, val smallDots: Boolean = false, val foot: Boolean = false) {
    BIKE_1X1(R.layout.hw_bike_1x1, false, false, false),
    BIKE_2X1(R.layout.hw_bike_2x1, true, true, false),
    BIKE_2X2(R.layout.hw_bike_2x2, true, true, true, smallDots = true),
    BIKE_4X1(R.layout.hw_bike_4x1, true, true, false),
    BIKE_4X2(R.layout.hw_bike_4x2, true, true, true),
    CARE(R.layout.hw_care_2x1, false, true, false),
    RIDE_2X1(R.layout.hw_ride_2x1, true, true, false),
    RIDE_2X2(R.layout.hw_ride_2x2, true, true, true, smallDots = true),
    RIDE_4X1(R.layout.hw_ride_4x1, false, false, false),
    RIDE_4X2(R.layout.hw_ride_4x2, true, true, true),
    REC_1X1(R.layout.hw_rec_1x1, false, true, false),
    REC_2X1(R.layout.hw_rec_2x1, false, true, false),
    REC_2X2(R.layout.hw_rec_2x2, false, true, true, smallDots = true),
    REC_4X2(R.layout.hw_rec_4x2, false, true, false),
    CAL_2X2(R.layout.hw_cal_2x2, true, true, false, foot = true),
    OTHER(0, false, false, false),
}

/** Everything the widgets show, read once per refresh. */
internal class Snapshot(
    val bikes: List<BikeCard>,
    val bikeIx: Int,
    val ready: Readiness,
    val rides: List<RideCard>,
    val routes: List<File?>,
    val newestEndMillis: Long?,
    val thumbs: List<File?>,
    val records: List<RecordCard>,
    val days: Map<LocalDate, Double>,
    val today: LocalDate,
    val sun: Pair<Long, Long>?,
    val now: Long,
    val film: FilmToday?,
    val filmRiding: List<Boolean>,
)

/**
 * The home-screen widgets: Bike, Film today, Daylight and Bike care before a ride; Last ride and
 * Moments after; Records; the Riding calendar. Each widget picks its design from its size. A tap
 * on a photo, route, record or month slides or fades to the next (ViewFlipper, the motion home
 * screens allow), and the data catches up a moment later. They refresh when bikes, settings,
 * rides or Reels change while the app runs, and every 30 min otherwise.
 */
object HomeWidgets {
    const val NEXT_BIKE = "bike"
    const val NEXT_RIDE = "ride"
    const val NEXT_RECORD = "record"
    const val TILE = "tile"
    const val MONTH = "month"
    const val YEAR = "year"

    private val PROVIDERS = listOf(
        BikeWidget::class.java, FilmTodayWidget::class.java, DaylightWidget::class.java, BikeCareWidget::class.java,
        LastRideWidget::class.java, MomentsWidget::class.java, RecordsWidget::class.java, CalendarWidget::class.java,
    )
    private val TILES = intArrayOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3, R.id.tile4, R.id.tile5)
    private val TILE_LABELS = intArrayOf(R.id.lab0, R.id.lab1, R.id.lab2, R.id.lab3, R.id.lab4, R.id.lab5)
    private val TILE_VALUES = intArrayOf(R.id.val0, R.id.val1, R.id.val2, R.id.val3, R.id.val4, R.id.val5)
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private const val GREY = 0xFF8B8B93.toInt()
    private const val OK = 0xFF4ADE80.toInt()
    private const val AMBER = 0xFFFBBF24.toInt()
    private const val ROSE = 0xFFFB7185.toInt()
    private const val VIOLET = 0xFFA5A1FF.toInt()
    private const val WHITE = 0xFFF4F4F5.toInt()

    @OptIn(FlowPreview::class)
    fun watch(context: Context, c: AppContainer, scope: CoroutineScope) {
        scope.launch {
            combine(c.settings.settings, c.bikes.observeBikes(), c.rides.observeCompleted(), c.reels.reels) { _, _, _, _ -> Unit }
                .debounce(1_500)
                .collect { runCatching { refresh(context, c) }.onFailure { c.errors.warn("Widgets", "Couldn't update the home-screen widgets", it) } }
        }
    }

    suspend fun refresh(context: Context, c: AppContainer, only: Set<Int>? = null) {
        val m = AppWidgetManager.getInstance(context)
        val all = PROVIDERS.associateWith { m.getAppWidgetIds(ComponentName(context, it)) }
        if (all.values.all { it.isEmpty() }) return
        val snap = withContext(Dispatchers.IO) { snapshot(context, c) }
        val art = WidgetArt(context)
        all.forEach { (cls, ids) ->
            ids.filter { only == null || it in only }.forEach { id ->
                runCatching { m.updateAppWidget(id, render(context, art, snap, cls, id, size(m, id))) }
                    .onFailure { c.errors.warn("Widgets", "Couldn't draw the ${cls.simpleName}", it) }
            }
        }
    }

    private fun size(m: AppWidgetManager, id: Int): WidgetSize {
        val o: Bundle = m.getAppWidgetOptions(id)
        return WidgetSize.of(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    }

    // ---- data -----------------------------------------------------------------------------

    private suspend fun snapshot(context: Context, c: AppContainer): Snapshot {
        val s = c.settings.settings.first()
        val garage = c.bikes.observeBikes().first()
        val all = c.rides.observeCompleted().first()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val bikes = HomeWidgetData.bikes(garage, all, BikeCare.decode(s.bikeCare), Fuel.decodeFills(s.fuelLog), { BikeColors.argb(BikeColors.of(it, garage)) }, now)
        val selected = QuickStart.bike(garage, s.selectedBikeId)
        val bikeIx = garage.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        prefs(context).edit().putString("bikes", bikes.joinToString(",") { it.id }).putInt("bikeIx", bikeIx).apply()

        val choice = MicChoice.decode(s.moments.mic)
        val external = s.moments.enabled && choice.type in listOf(MicType.USB, MicType.WIRED, MicType.BLUETOOTH)
        val connected = runCatching { Microphones.available(context) }.getOrDefault(emptyList())
        val battery = context.getSystemService<BatteryManager>()
        val ready = Readiness.of(
            locationAllowed = Permissions.hasFineLocation(context),
            gpsOn = runCatching { context.getSystemService<LocationManager>()?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(true),
            chosenMic = choice.label.takeIf { external },
            micConnected = connected.any { it.type == choice.type },
            freeBytes = runCatching { context.filesDir.usableSpace }.getOrNull(),
            batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            charging = battery?.isCharging == true,
            crashAlertOn = s.safety.crashDetection,
            contacts = s.safety.contacts.size,
        )

        val recent = HomeWidgetData.realRides(all).take(3)
        val moments = recent.associate { r -> r.id to runCatching { c.moments.forRide(r.id) }.getOrDefault(emptyList()) }
        val rides = recent.map { HomeWidgetData.ride(it, moments[it.id]?.size ?: 0, zone) }
        prefs(context).edit().putString("rides", rides.joinToString(",") { it.id }).apply()
        val routes = recent.map { r -> runCatching { c.journal.ride(r.id)?.let { c.journal.routePng(it) } }.getOrNull() }
        val thumbs = recent.firstOrNull()?.let { r -> moments[r.id].orEmpty().mapNotNull { it.thumb?.takeIf(File::isFile) ?: it.file.takeIf { f -> f.extension == "jpg" } }.take(3) }.orEmpty()

        val location = if (Permissions.hasFineLocation(context)) runCatching {
            val lm = context.getSystemService<LocationManager>()
            @Suppress("MissingPermission")
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).firstNotNullOfOrNull { lm?.getLastKnownLocation(it) }
        }.getOrNull() else null
        val today = LocalDate.now(zone)
        val sun = location?.let { HomeWidgetData.sunTimes(it.latitude, it.longitude, today) }

        val template = c.reels.reels.value
            .filter { it.template != null && it.deletedAt == null && it.script != null && TemplateBuilder.placeholders(it.plan).isNotEmpty() }
            .maxByOrNull { it.updatedAt }
        var riding = emptyList<Boolean>()
        val film = template?.let { p ->
            val script = p.script!!
            val open = TemplateBuilder.placeholders(p.plan).toSet()
            val shots = Placeholders.shots(script).filter { it.second.id in open }.map { it.second.id!! to it.second.board!! }
            val groups = ToFilm.groups(shots)
            riding = groups.map { it.riding }
            val total = Placeholders.shots(script).size
            FilmToday(p.id, p.title, total - open.size, total, groups.map { g ->
                g.where to g.shots.joinToString(" · ") { (sid, b) -> "#${Placeholders.number(script, sid).trimStart('0')} ${b.action}" }
            })
        }
        return Snapshot(bikes, bikeIx, ready, rides, routes, recent.firstOrNull()?.endTimeMillis, thumbs, HomeWidgetData.records(all, zone),
            HomeWidgetData.dayKm(all, zone), today, sun, now, film, riding)
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun render(ctx: Context, art: WidgetArt, s: Snapshot, cls: Class<*>, id: Int, size: WidgetSize): RemoteViews = when (cls) {
        BikeWidget::class.java -> bike(ctx, art, s, id, size)
        FilmTodayWidget::class.java -> film(ctx, art, s, id)
        DaylightWidget::class.java -> daylight(ctx, art, s, id)
        BikeCareWidget::class.java -> care(ctx, s, id)
        LastRideWidget::class.java -> lastRide(ctx, art, s, id, size)
        MomentsWidget::class.java -> moments(ctx, art, s, id)
        RecordsWidget::class.java -> records(ctx, art, s, id, size)
        else -> calendar(ctx, art, s, id, size)
    }

    private fun bike(ctx: Context, art: WidgetArt, s: Snapshot, id: Int, size: WidgetSize): RemoteViews {
        if (s.bikes.isEmpty()) return empty(ctx, id, "Add your bike in Keppo Moto", open(ctx))
        val shown = when {
            size.cols == 1 -> Shown.BIKE_1X1
            size.cols == 2 && size.rows == 1 -> Shown.BIKE_2X1
            size.cols == 2 -> Shown.BIKE_2X2
            size.rows == 1 -> Shown.BIKE_4X1
            else -> Shown.BIKE_4X2
        }
        remember(ctx, id, shown, s.bikes.size, s.bikeIx)
        val rv = RemoteViews(ctx.packageName, shown.layout)
        val ix = s.bikeIx.coerceIn(0, s.bikes.lastIndex)
        val readyColor = if (s.ready.ok) OK else AMBER
        if (shown == Shown.BIKE_1X1) {
            val b = s.bikes[ix]
            rv.setImageViewBitmap(R.id.photo, art.bike(photo(ctx, b), b.color, 84f, 96f, 24f))
            rv.setInt(R.id.ready_dot, "setColorFilter", readyColor)
            rv.setContentDescription(R.id.root, "Start ride on ${b.name}. ${s.ready.short}")
            rv.setOnClickPendingIntent(R.id.root, QuickStart.pendingIntent(ctx))
            return rv
        }
        val (pw, ph) = when (shown) { Shown.BIKE_2X1 -> 72f to 76f; Shown.BIKE_2X2 -> 156f to 96f; Shown.BIKE_4X1 -> 92f to 76f; else -> 140f to 176f }
        rv.removeAllViews(R.id.flip_photo)
        rv.removeAllViews(R.id.flip_text)
        s.bikes.forEach { b ->
            rv.addView(R.id.flip_photo, image(ctx, art.bike(photo(ctx, b), b.color, pw, ph, if (shown == Shown.BIKE_4X2) 18f else 16f)))
            rv.addView(R.id.flip_text, bikeText(ctx, shown, b, s.ready))
        }
        rv.setDisplayedChild(R.id.flip_photo, ix)
        rv.setDisplayedChild(R.id.flip_text, ix)
        if (shown.dots) {
            if (s.bikes.size > 1) rv.setImageViewBitmap(R.id.dots, art.dots(s.bikes.size, ix, shown.smallDots)) else rv.setViewVisibility(R.id.dots, View.GONE)
        }
        rv.setOnClickPendingIntent(R.id.flip_photo, if (s.bikes.size > 1) action(ctx, NEXT_BIKE, id) else open(ctx))
        rv.setOnClickPendingIntent(R.id.start, QuickStart.pendingIntent(ctx))
        rv.setOnClickPendingIntent(R.id.root, open(ctx))
        return rv
    }

    private fun bikeText(ctx: Context, shown: Shown, b: BikeCard, r: Readiness): RemoteViews {
        val readyColor = if (r.ok) OK else AMBER
        val careColor = when (b.careLevel) { 2 -> ROSE; 1 -> AMBER; else -> GREY }
        return when (shown) {
            Shown.BIKE_4X2 -> RemoteViews(ctx.packageName, R.layout.hw_item_bike_4x2).apply {
                setTextViewText(R.id.name, b.name)
                setTextViewText(R.id.odo, b.odometer ?: "Set the odometer in the app")
                setInt(R.id.ready_dot, "setColorFilter", readyColor)
                setTextViewText(R.id.ready, if (r.ok) "All set" else "${r.problem} · ${r.fix}")
                setTextColor(R.id.ready, if (r.ok) WHITE else AMBER)
                setTextViewText(R.id.fuel, b.fuel ?: "Log fill-ups to see the range")
                if (b.care == null) setViewVisibility(R.id.care_row, View.GONE) else {
                    setTextViewText(R.id.care, b.care)
                    setTextColor(R.id.care, careColor)
                    setInt(R.id.care_icon, "setColorFilter", careColor)
                }
            }
            Shown.BIKE_4X1 -> RemoteViews(ctx.packageName, R.layout.hw_item_bike_4x1).apply {
                setInt(R.id.ready_dot, "setColorFilter", readyColor)
                setTextViewText(R.id.name, if (r.ok) b.name else "${b.name} · ${r.problem}")
                setTextViewText(R.id.line, listOfNotNull(b.fuel, b.careShort?.let { colored(it, careColor) }).joinTo(android.text.SpannableStringBuilder(), " · ").ifEmpty { b.odometer ?: "Ready when you are" })
            }
            else -> RemoteViews(ctx.packageName, R.layout.hw_item_bike_small).apply {
                setTextViewText(R.id.name, b.name)
                setInt(R.id.ready_dot, "setColorFilter", readyColor)
                setTextViewText(R.id.ready, if (r.ok) "All set" else if (shown == Shown.BIKE_2X2) "${r.problem} · tap to fix" else r.problem)
                setTextColor(R.id.ready, if (r.ok) WHITE else AMBER)
            }
        }
    }

    private fun care(ctx: Context, s: Snapshot, id: Int): RemoteViews {
        if (s.bikes.isEmpty()) return empty(ctx, id, "Add your bike in Keppo Moto", open(ctx))
        remember(ctx, id, Shown.CARE, s.bikes.size, s.bikeIx)
        val rv = RemoteViews(ctx.packageName, R.layout.hw_care_2x1)
        rv.removeAllViews(R.id.flip_text)
        s.bikes.forEach { b ->
            val careColor = when (b.careLevel) { 2 -> ROSE; 1 -> AMBER; else -> WHITE }
            rv.addView(R.id.flip_text, RemoteViews(ctx.packageName, R.layout.hw_item_care).apply {
                setInt(R.id.bike_dot, "setColorFilter", b.color)
                setTextViewText(R.id.name, b.name)
                setTextViewText(R.id.fuel, b.fuel ?: "Log a fill-up")
                setTextViewText(R.id.care, b.care?.substringBefore(" · ")?.let { name -> "$name ${b.careShort?.substringAfter(' ')?.let { "· $it" }.orEmpty()}" } ?: "No reminders set")
                setTextColor(R.id.care, careColor)
                setInt(R.id.care_icon, "setColorFilter", if (b.care == null) GREY else careColor)
            })
        }
        rv.setDisplayedChild(R.id.flip_text, s.bikeIx.coerceIn(0, s.bikes.lastIndex))
        rv.setOnClickPendingIntent(R.id.root, if (s.bikes.size > 1) action(ctx, NEXT_BIKE, id) else open(ctx))
        return rv
    }

    private fun film(ctx: Context, art: WidgetArt, s: Snapshot, id: Int): RemoteViews {
        remember(ctx, id, Shown.OTHER, 0, 0)
        val rv = RemoteViews(ctx.packageName, R.layout.hw_film_4x2)
        val f = s.film
        if (f == null) {
            rv.setTextViewText(R.id.title, "Nothing to film")
            rv.setTextViewText(R.id.count, "")
            rv.setViewVisibility(R.id.progress, View.GONE)
            rv.setViewVisibility(R.id.g0, View.GONE)
            rv.setViewVisibility(R.id.g1, View.GONE)
            rv.setTextViewText(R.id.hint, "Plan a Reel with Storyboard in Studio, and its shots show up here.")
            rv.setOnClickPendingIntent(R.id.root, open(ctx))
            rv.setOnClickPendingIntent(R.id.start, QuickStart.pendingIntent(ctx))
            return rv
        }
        rv.setTextViewText(R.id.title, f.title)
        rv.setTextViewText(R.id.count, "${f.done}/${f.total}")
        rv.setImageViewBitmap(R.id.progress, art.progress(f.done, f.total, 300f))
        listOf(Triple(R.id.g0, R.id.g0_where, R.id.g0_shots), Triple(R.id.g1, R.id.g1_where, R.id.g1_shots)).forEachIndexed { k, (row, where, shots) ->
            val g = f.groups.getOrNull(k)
            if (g == null) rv.setViewVisibility(row, View.GONE) else {
                rv.setTextViewText(where, g.first)
                rv.setTextViewText(shots, if (k == 1 && f.groups.size > 2) "${g.second} · +${f.groups.size - 2} more setups" else g.second)
                rv.setImageViewResource(if (k == 0) R.id.g0_icon else R.id.g1_icon, if (s.filmRiding.getOrNull(k) == true) R.drawable.hw_ic_camera else R.drawable.hw_ic_tripod)
            }
        }
        rv.setTextViewText(R.id.hint, if (s.filmRiding.any { it }) "Riding shots pop up as you ride" else "Off-bike shots: Record from the shot")
        rv.setOnClickPendingIntent(R.id.root, openReel(ctx, f.reelId))
        rv.setOnClickPendingIntent(R.id.start, QuickStart.pendingIntent(ctx))
        return rv
    }

    private fun daylight(ctx: Context, art: WidgetArt, s: Snapshot, id: Int): RemoteViews {
        remember(ctx, id, Shown.OTHER, 0, 0)
        val rv = RemoteViews(ctx.packageName, R.layout.hw_daylight_2x1)
        rv.setOnClickPendingIntent(R.id.root, open(ctx))
        val sun = s.sun
        if (sun == null) {
            rv.setImageViewBitmap(R.id.arc, art.sunArc(0f, 148f, 34f))
            rv.setTextViewText(R.id.time, "--:--")
            rv.setTextViewText(R.id.sub, "allow location to see sunset")
            return rv
        }
        val (rise, set) = sun
        fun hm(t: Long) = HHMM.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
        val t = ((s.now - rise).toFloat() / (set - rise)).coerceIn(0f, 1f)
        rv.setImageViewBitmap(R.id.arc, art.sunArc(t, 148f, 34f))
        when {
            s.now < rise -> { rv.setTextViewText(R.id.time, hm(rise)); rv.setTextViewText(R.id.sub, "sunrise · light till ${hm(set)}") }
            s.now > set -> { rv.setTextViewText(R.id.time, hm(set)); rv.setTextViewText(R.id.sub, "sun set · dark now") }
            else -> {
                val left = (set - s.now) / 60_000
                rv.setTextViewText(R.id.time, hm(set))
                rv.setTextViewText(R.id.sub, "sunset · ${left / 60} h ${left % 60} m left")
            }
        }
        return rv
    }

    private fun lastRide(ctx: Context, art: WidgetArt, s: Snapshot, id: Int, size: WidgetSize): RemoteViews {
        if (s.rides.isEmpty()) return empty(ctx, id, "Your rides show up here after your first ride.", open(ctx))
        val shown = when {
            size.cols <= 2 && size.rows == 1 -> Shown.RIDE_2X1
            size.cols <= 2 -> Shown.RIDE_2X2
            size.rows == 1 -> Shown.RIDE_4X1
            else -> Shown.RIDE_4X2
        }
        val i = index(ctx, id, s.rides.size)
        remember(ctx, id, shown, s.rides.size, i)
        val rv = RemoteViews(ctx.packageName, shown.layout)
        if (shown == Shown.RIDE_4X1) {
            val r = s.rides.first()
            val fresh = s.newestEndMillis?.let { s.now - it < 12 * 3_600_000L } == true
            rv.setTextViewText(R.id.title, "${if (fresh) "Ride saved" else "Last ride · ${r.day.lowercase().replaceFirstChar { it.uppercase() }}"} · ${r.km} ${Units.distanceUnit}")
            rv.setTextViewText(R.id.line, "${r.moments} moments · ${r.time} h · ${r.name}")
            rv.setOnClickPendingIntent(R.id.share, openRide(ctx, r.id, share = true))
            rv.setOnClickPendingIntent(R.id.open, openRide(ctx, r.id))
            rv.setOnClickPendingIntent(R.id.root, openRide(ctx, r.id))
            return rv
        }
        val (pw, ph) = when (shown) { Shown.RIDE_2X1 -> 58f to 76f; Shown.RIDE_2X2 -> 156f to 96f; else -> 124f to 176f }
        rv.removeAllViews(R.id.flip_photo)
        rv.removeAllViews(R.id.flip_text)
        s.rides.forEachIndexed { k, r ->
            rv.addView(R.id.flip_photo, image(ctx, art.route(s.routes.getOrNull(k), pw, ph, 16f)))
            rv.addView(R.id.flip_text, rideText(ctx, shown, r))
        }
        rv.setDisplayedChild(R.id.flip_photo, i)
        rv.setDisplayedChild(R.id.flip_text, i)
        if (shown.dots) {
            if (s.rides.size > 1) rv.setImageViewBitmap(R.id.dots, art.dots(s.rides.size, i, shown.smallDots)) else rv.setViewVisibility(R.id.dots, View.GONE)
        }
        val cur = s.rides[i]
        rv.setOnClickPendingIntent(R.id.flip_photo, if (s.rides.size > 1) action(ctx, NEXT_RIDE, id) else openRide(ctx, cur.id))
        rv.setOnClickPendingIntent(R.id.root, openRide(ctx, cur.id))
        if (shown == Shown.RIDE_4X2) {
            rv.setTextViewText(R.id.moments_text, "${cur.moments} moments")
            rv.setOnClickPendingIntent(R.id.moments, openRide(ctx, cur.id))
            rv.setOnClickPendingIntent(R.id.share, openRide(ctx, cur.id, share = true))
        }
        return rv
    }

    private fun rideText(ctx: Context, shown: Shown, r: RideCard): RemoteViews {
        val leanText = r.lean?.let { SpannableString("$it ${if (r.leanLeft) "L" else "R"}").apply { setSpan(ForegroundColorSpan(if (r.leanLeft) VIOLET else ROSE), it.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } } ?: "–"
        return when (shown) {
            Shown.RIDE_4X2 -> RemoteViews(ctx.packageName, R.layout.hw_item_ride_4x2).apply {
                setTextViewText(R.id.`when`, r.whenText)
                setTextViewText(R.id.name, r.name)
                setTextViewText(R.id.km, unit(r.km, Units.distanceUnit))
                setTextViewText(R.id.time, unit(r.time, "h"))
                setTextViewText(R.id.top, r.top?.let { unit(it, Units.speedUnit) } ?: "–")
                setTextViewText(R.id.lean, leanText)
            }
            Shown.RIDE_2X2 -> RemoteViews(ctx.packageName, R.layout.hw_item_ride_2x2).apply {
                setTextViewText(R.id.`when`, "${r.day.lowercase().replaceFirstChar { it.uppercase() }} · ${r.name}")
                setTextViewText(R.id.km, unit(r.km, Units.distanceUnit))
                setTextViewText(R.id.line, listOfNotNull(r.time, r.top?.let { "top $it" }, r.lean).joinToString("  ·  "))
            }
            else -> RemoteViews(ctx.packageName, R.layout.hw_item_ride_2x1).apply {
                setTextViewText(R.id.`when`, r.day)
                setTextViewText(R.id.km, unit(r.km, Units.distanceUnit))
                setTextViewText(R.id.line, listOfNotNull(r.time, r.top?.let { "top $it" }).joinToString(" · "))
            }
        }
    }

    private fun moments(ctx: Context, art: WidgetArt, s: Snapshot, id: Int): RemoteViews {
        val r = s.rides.firstOrNull() ?: return empty(ctx, id, "Moments from your rides show up here.", open(ctx))
        remember(ctx, id, Shown.OTHER, 0, 0)
        return RemoteViews(ctx.packageName, R.layout.hw_moments_2x1).apply {
            setImageViewBitmap(R.id.fan, art.fan(s.thumbs.ifEmpty { listOf(null, null, null) }, 70f, 76f))
            setTextViewText(R.id.count, "${r.moments}")
            setTextViewText(R.id.label, if (r.moments == 1) "moment" else "moments")
            setTextViewText(R.id.`when`, r.whenText)
            setOnClickPendingIntent(R.id.root, openRide(ctx, r.id))
        }
    }

    private fun records(ctx: Context, art: WidgetArt, s: Snapshot, id: Int, size: WidgetSize): RemoteViews {
        val list = s.records
        if (list.isEmpty()) return empty(ctx, id, "Records show up after your first ride.", open(ctx))
        val shown = when {
            size.cols == 1 -> Shown.REC_1X1
            size.rows == 1 -> Shown.REC_2X1
            size.cols == 2 -> Shown.REC_2X2
            else -> Shown.REC_4X2
        }
        val pairs = pairs(list)
        val n = when (shown) { Shown.REC_2X1 -> pairs.size; else -> list.size }
        val i = index(ctx, id, n)
        remember(ctx, id, shown, n, i)
        val rv = RemoteViews(ctx.packageName, shown.layout)
        rv.removeAllViews(R.id.flip_text)
        when (shown) {
            Shown.REC_1X1 -> list.forEach { r ->
                rv.addView(R.id.flip_text, RemoteViews(ctx.packageName, R.layout.hw_item_rec_1x1).apply {
                    setTextViewText(R.id.value, r.value); setTextViewText(R.id.label, r.short); setTextViewText(R.id.unit, r.unit); setTextColor(R.id.unit, leanColor(r))
                })
            }
            Shown.REC_2X1 -> pairs.forEach { (a, b) ->
                rv.addView(R.id.flip_text, RemoteViews(ctx.packageName, R.layout.hw_item_rec_pair).apply {
                    setTextViewText(R.id.label_a, a.short); setTextViewText(R.id.value_a, a.value); setTextViewText(R.id.unit_a, a.unit); setTextColor(R.id.unit_a, leanColor(a))
                    if (b == null) { setTextViewText(R.id.label_b, ""); setTextViewText(R.id.value_b, ""); setTextViewText(R.id.unit_b, "") } else {
                        setTextViewText(R.id.label_b, b.short); setTextViewText(R.id.value_b, b.value); setTextViewText(R.id.unit_b, b.unit); setTextColor(R.id.unit_b, leanColor(b))
                    }
                })
            }
            Shown.REC_2X2 -> {
                list.forEach { r ->
                    rv.addView(R.id.flip_text, RemoteViews(ctx.packageName, R.layout.hw_item_rec_card).apply {
                        setTextViewText(R.id.label, r.label)
                        setViewVisibility(R.id.fresh, if (r.fresh) View.VISIBLE else View.GONE)
                        setTextViewText(R.id.value, r.value)
                        setTextViewText(R.id.unit, if (r.lean != null) "leaning ${r.unit}" else r.unit)
                        setTextColor(R.id.unit, leanColor(r))
                        setTextViewText(R.id.context, r.context)
                    })
                }
                rv.setImageViewBitmap(R.id.dots, art.dots(list.size, i, small = true))
            }
            else -> {
                TILES.forEachIndexed { k, tile ->
                    val r = list.getOrNull(k)
                    if (r == null) { rv.setViewVisibility(tile, View.INVISIBLE); return@forEachIndexed }
                    rv.setTextViewText(TILE_LABELS[k], if (r.fresh) SpannableString("${r.label}  ●").apply { setSpan(ForegroundColorSpan(WidgetArt.BRAND), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else r.label)
                    rv.setTextViewText(TILE_VALUES[k], if (r.lean != null) r.value else unit(r.value, r.unit))
                    rv.setInt(tile, "setBackgroundResource", if (k == i) R.drawable.hw_tile_sel else R.drawable.hw_chip)
                    rv.setOnClickPendingIntent(tile, action(ctx, TILE, id, k))
                    rv.addView(R.id.flip_text, RemoteViews(ctx.packageName, R.layout.hw_item_line).apply {
                        setTextViewText(R.id.text, "${r.value}${if (r.lean == null) " " + r.unit else ""} · ${r.context}")
                    })
                }
            }
        }
        rv.setDisplayedChild(R.id.flip_text, i)
        if (shown != Shown.REC_4X2) rv.setOnClickPendingIntent(R.id.root, if (n > 1) action(ctx, NEXT_RECORD, id) else open(ctx))
        return rv
    }

    private fun calendar(ctx: Context, art: WidgetArt, s: Snapshot, id: Int, size: WidgetSize): RemoteViews {
        val days = s.days
        val today = s.today
        val fmtKm = { km: Double -> String.format(Locale.US, "%,d", Units.fromKm(km).roundToInt()) }
        val streak = HomeWidgetData.streak(days, today)
        val streakText = if (streak >= 2) "$streak-day streak" else if (streak == 1) "Rode today" else "Last 7 days"
        return when {
            size.rows == 1 -> {
                remember(ctx, id, Shown.OTHER, 0, 0)
                val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
                RemoteViews(ctx.packageName, R.layout.hw_cal_2x1).apply {
                    setTextViewText(R.id.streak, streakText)
                    setTextViewText(R.id.total, "${fmtKm(week.sumOf { days[it] ?: 0.0 })} ${Units.distanceUnit}")
                    setImageViewBitmap(R.id.bars, art.weekBars(week.map { days[it] ?: 0.0 }, 148f, 44f))
                    setTextViewText(R.id.days, week.joinToString("") { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) })
                    setOnClickPendingIntent(R.id.root, open(ctx))
                }
            }
            size.cols <= 2 -> {
                val i = index(ctx, id, 3, default = 2)
                remember(ctx, id, Shown.CAL_2X2, 3, i)
                RemoteViews(ctx.packageName, R.layout.hw_cal_2x2).apply {
                    removeAllViews(R.id.flip_photo); removeAllViews(R.id.flip_text); removeAllViews(R.id.flip_foot)
                    (2 downTo 0).forEach { back ->
                        val first = today.withDayOfMonth(1).minusMonths(back.toLong())
                        val inMonth = days.filterKeys { it.year == first.year && it.month == first.month }
                        addView(R.id.flip_photo, RemoteViews(ctx.packageName, R.layout.hw_item_month).apply { setImageViewBitmap(R.id.img, art.month(days, first, today, 18f, 3f)) })
                        addView(R.id.flip_text, RemoteViews(ctx.packageName, R.layout.hw_item_cal_head).apply {
                            setTextViewText(R.id.name, first.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                            setTextViewText(R.id.days, "${inMonth.size} days")
                        })
                        addView(R.id.flip_foot, RemoteViews(ctx.packageName, R.layout.hw_item_text).apply { setTextViewText(R.id.text, "${fmtKm(inMonth.values.sum())} ${Units.distanceUnit} this month") })
                    }
                    setDisplayedChild(R.id.flip_photo, i); setDisplayedChild(R.id.flip_text, i); setDisplayedChild(R.id.flip_foot, i)
                    setOnClickPendingIntent(R.id.prev, action(ctx, MONTH, id, -1))
                    setOnClickPendingIntent(R.id.next, action(ctx, MONTH, id, 1))
                    setOnClickPendingIntent(R.id.root, open(ctx))
                }
            }
            size.rows == 2 -> {
                remember(ctx, id, Shown.OTHER, 0, 0)
                val start = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong()).minusWeeks(12)
                val window = days.filterKeys { !it.isBefore(start) && !it.isAfter(today) }
                RemoteViews(ctx.packageName, R.layout.hw_cal_4x2).apply {
                    setImageViewBitmap(R.id.grid, art.weeks(days, today, 13f, 3f))
                    setTextViewText(R.id.months, generateSequence(start.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(today) }
                        .joinToString("      ") { it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) })
                    setTextViewText(R.id.days, "${window.size}")
                    setTextViewText(R.id.km, fmtKm(window.values.sum()))
                    setTextViewText(R.id.unit, Units.distanceUnit)
                    setTextViewText(R.id.streak, if (streak >= 2) "$streak-day streak" else "")
                    setOnClickPendingIntent(R.id.root, open(ctx))
                }
            }
            else -> {
                remember(ctx, id, Shown.OTHER, 0, 0)
                val sel = prefs(ctx).getInt("m.$id", today.monthValue - 1).coerceIn(0, today.monthValue - 1)
                val year = days.filterKeys { it.year == today.year }
                val selDays = year.filterKeys { it.monthValue == sel + 1 }
                var best = 0
                var run = 0
                var d = LocalDate.of(today.year, 1, 1)
                while (!d.isAfter(today)) { if (year.containsKey(d)) { run++; best = maxOf(best, run) } else run = 0; d = d.plusDays(1) }
                RemoteViews(ctx.packageName, R.layout.hw_cal_4x3).apply {
                    setTextViewText(R.id.year, "${today.year}")
                    setTextViewText(R.id.total, "${year.size} days · ${fmtKm(year.values.sum())} ${Units.distanceUnit}")
                    setImageViewBitmap(R.id.grid, art.year(days, today.year, today, sel, 332f, 196f))
                    setTextViewText(R.id.summary, "${java.time.Month.of(sel + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH)} · ${selDays.size} days · ${fmtKm(selDays.values.sum())} ${Units.distanceUnit}")
                    setTextViewText(R.id.streak, if (best >= 2) "Longest streak $best days" else "")
                    setImageViewBitmap(R.id.legend, art.legend())
                    setOnClickPendingIntent(R.id.grid, action(ctx, YEAR, id))
                    setOnClickPendingIntent(R.id.root, open(ctx))
                }
            }
        }
    }

    // ---- taps -------------------------------------------------------------------------------

    internal suspend fun handle(ctx: Context, c: AppContainer, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        val what = intent.data?.host ?: return
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return
        val arg = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val m = AppWidgetManager.getInstance(ctx)
        val p = prefs(ctx)
        val art = WidgetArt(ctx)
        when (what) {
            NEXT_BIKE -> {
                val ids = p.getString("bikes", "").orEmpty().split(',').filter { it.isNotBlank() }
                if (ids.size < 2) return
                val next = (p.getInt("bikeIx", 0) + 1) % ids.size
                p.edit().putInt("bikeIx", next).apply()
                // Every bike widget and Bike care slides together; the bike becomes the selected one.
                listOf(BikeWidget::class.java, BikeCareWidget::class.java).flatMap { m.getAppWidgetIds(ComponentName(ctx, it)).toList() }.forEach { w ->
                    val shown = shown(ctx, w) ?: return@forEach
                    if (shown.layout == 0 || shown == Shown.BIKE_1X1) return@forEach
                    runCatching { m.partiallyUpdateAppWidget(w, flip(ctx, art, shown, next, ids.size)) }
                }
                c.settings.setSelectedBike(ids[next])
            }
            NEXT_RIDE, NEXT_RECORD -> {
                val shown = shown(ctx, id) ?: return
                val n = p.getInt("n.$id", 1)
                if (n < 2) return
                val i = (p.getInt("i.$id", 0) + 1) % n
                p.edit().putInt("i.$id", i).apply()
                val rv = flip(ctx, art, shown, i, n)
                if (what == NEXT_RIDE) p.getString("rides", "").orEmpty().split(',').getOrNull(i)?.takeIf { it.isNotBlank() }?.let { rv.setOnClickPendingIntent(R.id.root, openRide(ctx, it)) }
                m.partiallyUpdateAppWidget(id, rv)
            }
            TILE -> {
                p.edit().putInt("i.$id", arg).apply()
                val rv = RemoteViews(ctx.packageName, R.layout.hw_rec_4x2)
                TILES.forEachIndexed { k, tile -> rv.setInt(tile, "setBackgroundResource", if (k == arg) R.drawable.hw_tile_sel else R.drawable.hw_chip) }
                rv.setDisplayedChild(R.id.flip_text, arg)
                m.partiallyUpdateAppWidget(id, rv)
            }
            MONTH -> {
                val i = (p.getInt("i.$id", 2) + arg).coerceIn(0, 2)
                p.edit().putInt("i.$id", i).apply()
                m.partiallyUpdateAppWidget(id, RemoteViews(ctx.packageName, R.layout.hw_cal_2x2).apply {
                    setDisplayedChild(R.id.flip_photo, i); setDisplayedChild(R.id.flip_text, i); setDisplayedChild(R.id.flip_foot, i)
                })
            }
            YEAR -> {
                val now = LocalDate.now().monthValue - 1
                val sel = p.getInt("m.$id", now)
                p.edit().putInt("m.$id", if (sel <= 0) now else sel - 1).apply()
                refresh(ctx, c, setOf(id))
            }
        }
    }

    /** The moving parts of [shown] set to item [i] of [n]. */
    private fun flip(ctx: Context, art: WidgetArt, shown: Shown, i: Int, n: Int) = RemoteViews(ctx.packageName, shown.layout).apply {
        if (shown.photo) setDisplayedChild(R.id.flip_photo, i)
        if (shown.text) setDisplayedChild(R.id.flip_text, i)
        if (shown.foot) setDisplayedChild(R.id.flip_foot, i)
        if (shown.dots) setImageViewBitmap(R.id.dots, art.dots(n, i, shown.smallDots))
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)

    private fun remember(ctx: Context, id: Int, shown: Shown, n: Int, i: Int) {
        prefs(ctx).edit().putString("shown.$id", shown.name).putInt("n.$id", n).putInt("i.$id", i).apply()
    }

    private fun shown(ctx: Context, id: Int): Shown? = prefs(ctx).getString("shown.$id", null)?.let { name -> Shown.entries.firstOrNull { it.name == name } }

    /** The item a widget is on, kept within [n]. */
    private fun index(ctx: Context, id: Int, n: Int, default: Int = 0): Int = prefs(ctx).getInt("i.$id", default).let { if (it in 0 until n) it else default.coerceIn(0, (n - 1).coerceAtLeast(0)) }

    internal fun forget(ctx: Context, ids: IntArray) {
        val e = prefs(ctx).edit()
        ids.forEach { id -> listOf("shown", "n", "i", "m").forEach { e.remove("$it.$id") } }
        e.apply()
    }

    private fun pairs(list: List<RecordCard>): List<Pair<RecordCard, RecordCard?>> =
        listOf(0 to 1, 3 to 4, 2 to 5).mapNotNull { (a, b) -> list.getOrNull(a)?.let { it to list.getOrNull(b) } }

    private fun leanColor(r: RecordCard) = when (r.lean) { 'L' -> VIOLET; 'R' -> ROSE; else -> GREY }

    private fun unit(value: String, unit: String): CharSequence = SpannableString("$value $unit").apply {
        setSpan(RelativeSizeSpan(0.6f), value.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(GREY), value.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun colored(text: String, color: Int): CharSequence = SpannableString(text).apply { setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    private fun image(ctx: Context, bmp: android.graphics.Bitmap) = RemoteViews(ctx.packageName, R.layout.hw_item_image).apply { setImageViewBitmap(R.id.img, bmp) }

    private fun photo(ctx: Context, b: BikeCard): File? = b.photo?.let { File(File(ctx.filesDir, "bikes"), it) }

    private fun empty(ctx: Context, id: Int, text: String, onTap: PendingIntent): RemoteViews {
        remember(ctx, id, Shown.OTHER, 0, 0)
        return RemoteViews(ctx.packageName, R.layout.hw_loading).apply {
            setTextViewText(R.id.hw_empty_text, text)
            setOnClickPendingIntent(R.id.hw_empty_root, onTap)
        }
    }

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 60, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openRide(ctx: Context, rideId: String, share: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, 61,
        Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_RIDE).setData(Uri.parse("keppo-widget://open/$rideId/$share"))
            .putExtra("rideId", rideId).putExtra(MainActivity.EXTRA_SHARE, share).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openReel(ctx: Context, reelId: String): PendingIntent = PendingIntent.getActivity(
        ctx, 62,
        Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_RIDE).setData(Uri.parse("keppo-widget://reel/$reelId"))
            .putExtra("rideId", "").putExtra(MainActivity.EXTRA_REEL, reelId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A tap the widgets handle themselves; the address makes each widget's and item's distinct. */
    private fun action(ctx: Context, what: String, id: Int, arg: Int = 0): PendingIntent = PendingIntent.getBroadcast(
        ctx, 63, Intent(ctx, HomeWidgetActions::class.java).setData(Uri.parse("keppo-widget://$what/$id/$arg")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** All home widgets update the same way: one shared snapshot, each drawn at its own size. */
abstract class HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = later(context, ids.toSet())

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = later(context, setOf(id))

    override fun onDeleted(context: Context, ids: IntArray) = HomeWidgets.forget(context, ids)

    private fun later(context: Context, ids: Set<Int>) {
        val c = (context.applicationContext as RideTrackApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                runCatching { HomeWidgets.refresh(context.applicationContext, c, ids) }.onFailure { c.errors.warn("Widgets", "Couldn't update a widget", it) }
            } finally {
                pending.finish()
            }
        }
    }
}

class BikeWidget : HomeWidgetProvider()
class FilmTodayWidget : HomeWidgetProvider()
class DaylightWidget : HomeWidgetProvider()
class BikeCareWidget : HomeWidgetProvider()
class LastRideWidget : HomeWidgetProvider()
class MomentsWidget : HomeWidgetProvider()
class RecordsWidget : HomeWidgetProvider()
class CalendarWidget : HomeWidgetProvider()

/** Taps on the widgets: next bike, ride or record, a record tile, a month. */
class HomeWidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as RideTrackApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                runCatching { HomeWidgets.handle(context.applicationContext, c, intent) }.onFailure { c.errors.warn("Widgets", "Couldn't handle a widget tap", it) }
            } finally {
                pending.finish()
            }
        }
    }
}
