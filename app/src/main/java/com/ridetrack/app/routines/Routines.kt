package com.ridetrack.app.routines

import com.ridetrack.telemetry.math.Geo
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** When a routine runs. */
enum class RoutineTrigger(val label: String, val short: String) {
    RIDE_START("When a ride starts", "Ride starts"),
    RIDE_END("When a ride ends", "Ride ends"),
    ARRIVE("When I arrive at a place", "I arrive"),
}

/** One step of a routine. */
sealed interface RoutineAction {
    val label: String

    /** Plays whatever music app was playing last (a "play" press, as headphones send). */
    data object ResumeMusic : RoutineAction { override val label = "Resume music" }
    data object PauseMusic : RoutineAction { override val label = "Pause music" }
    data object OpenMaps : RoutineAction { override val label = "Open Maps" }
    data class OpenApp(val pkg: String, val name: String) : RoutineAction { override val label get() = "Open $name" }
    data class MediaVolume(val percent: Int) : RoutineAction { override val label get() = "Media volume $percent%" }
    data object DoNotDisturb : RoutineAction { override val label = "Do Not Disturb on" }
    /** Only on arrival: ends and saves the ride. */
    data object EndRide : RoutineAction { override val label = "End the ride" }
}

data class RoutinePlace(val name: String, val lat: Double, val lon: Double, val radiusM: Int = 150)

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val trigger: RoutineTrigger,
    val place: RoutinePlace? = null,
    val actions: List<RoutineAction>,
) {
    /** The line under its name: "Ride starts · Resume music, Open Maps". */
    val summary: String
        get() = listOfNotNull(
            if (trigger == RoutineTrigger.ARRIVE) "Arrive at ${place?.name ?: "a place"}" else trigger.short,
            actions.joinToString(", ") { it.label }.ifEmpty { "No steps yet" },
        ).joinToString(" · ")

    /** Steps this routine's trigger allows (ending the ride only makes sense on arrival). */
    val runnable: List<RoutineAction> get() = actions.filter { it != RoutineAction.EndRide || trigger == RoutineTrigger.ARRIVE }
}

/** Pure: saving routines as text, the starters, and what each step changes. Unit-tested. */
object Routines {
    /** Ready-made routines offered when there are none. */
    fun starters(): List<Routine> = listOf(
        Routine(name = "Ride start", trigger = RoutineTrigger.RIDE_START, actions = listOf(RoutineAction.ResumeMusic, RoutineAction.OpenMaps)),
        Routine(name = "Ride end", trigger = RoutineTrigger.RIDE_END, actions = listOf(RoutineAction.PauseMusic)),
    )

    /** Pause music sits better after a ride; the rest suit its start. */
    fun choices(trigger: RoutineTrigger): List<RoutineAction> = listOfNotNull(
        RoutineAction.ResumeMusic,
        RoutineAction.PauseMusic,
        RoutineAction.OpenMaps,
        RoutineAction.MediaVolume(70),
        RoutineAction.DoNotDisturb,
        RoutineAction.EndRide.takeIf { trigger == RoutineTrigger.ARRIVE },
    )

    // One routine per line: fields URL-encoded and joined with "|"; steps joined with ",".
    fun encode(list: List<Routine>): String = list.joinToString("\n") { r ->
        listOf(
            r.id, if (r.enabled) "1" else "0", r.trigger.name, r.name,
            r.place?.name.orEmpty(), r.place?.lat?.toString().orEmpty(), r.place?.lon?.toString().orEmpty(), r.place?.radiusM?.toString().orEmpty(),
            r.actions.joinToString(",") { e(step(it)) },
        ).joinToString("|") { e(it) }
    }

    fun decode(value: String?): List<Routine> = value.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        runCatching {
            val f = line.split("|").map { d(it) }
            val trigger = RoutineTrigger.valueOf(f[2])
            val place = f[5].toDoubleOrNull()?.let { lat -> RoutinePlace(f[4], lat, f[6].toDouble(), f[7].toIntOrNull() ?: 150) }
            Routine(f[0], f[3], f[1] == "1", trigger, place, f[8].split(",").filter { it.isNotEmpty() }.mapNotNull { parse(d(it)) })
        }.getOrNull()
    }

    private fun step(a: RoutineAction): String = when (a) {
        RoutineAction.ResumeMusic -> "resume"
        RoutineAction.PauseMusic -> "pause"
        RoutineAction.OpenMaps -> "maps"
        is RoutineAction.OpenApp -> "app:${e(a.pkg)}:${e(a.name)}"
        is RoutineAction.MediaVolume -> "volume:${a.percent}"
        RoutineAction.DoNotDisturb -> "dnd"
        RoutineAction.EndRide -> "end"
    }

    private fun parse(s: String): RoutineAction? {
        val p = s.split(":")
        return when (p[0]) {
            "resume" -> RoutineAction.ResumeMusic
            "pause" -> RoutineAction.PauseMusic
            "maps" -> RoutineAction.OpenMaps
            "app" -> p.getOrNull(2)?.let { RoutineAction.OpenApp(d(p[1]), d(it)) }
            "volume" -> p.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)?.let { RoutineAction.MediaVolume(it) }
            "dnd" -> RoutineAction.DoNotDisturb
            "end" -> RoutineAction.EndRide
            else -> null
        }
    }

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun d(s: String) = URLDecoder.decode(s, "UTF-8")
}

/**
 * Arriving at a routine's place during a ride: you've been away from it (at least 300 m past
 * its edge, so a ride that starts at home doesn't "arrive" at once), then stay inside it for
 * [dwellMillis]. Each place arrives once per ride. Pure; one per ride.
 */
class ArrivalWatch(private val dwellMillis: Long = 20_000) {
    private val away = HashSet<String>()
    private val insideSince = HashMap<String, Long>()
    private val done = HashSet<String>()

    /** The routines (of [list]) that have just arrived, at [lat],[lon] at [timeMillis]. */
    fun update(list: List<Routine>, lat: Double, lon: Double, timeMillis: Long): List<Routine> = list.filter { r ->
        val p = r.place ?: return@filter false
        if (!r.enabled || r.trigger != RoutineTrigger.ARRIVE || r.id in done) return@filter false
        val d = Geo.distanceM(lat, lon, p.lat, p.lon)
        if (d > p.radiusM + AWAY_M) away += r.id
        if (d > p.radiusM || r.id !in away) {
            insideSince.remove(r.id)
            return@filter false
        }
        val since = insideSince.getOrPut(r.id) { timeMillis }
        (timeMillis - since >= dwellMillis).also { if (it) done += r.id }
    }

    companion object {
        const val AWAY_M = 300.0
    }
}
