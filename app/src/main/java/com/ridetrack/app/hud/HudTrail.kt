package com.ridetrack.app.hud

import android.content.Context

/**
 * Why the riding pop-up went away, counted per ride so a ride that ends with "it kept
 * disappearing" says which rule did it. Kept on disk: an app restart mid-ride is one of the reasons.
 */
class HudTrail(private val store: Store) {

    enum class Why(val key: String, val label: String, val expected: Boolean) {
        APP_OPEN("app", "Keppo Moto was opened", true),
        DRAGGED("drag", "dragged onto the ✕", true),
        TURNED_OFF("off", "turned off in Settings", true),
        NO_PERMISSION("perm", "the pop-up permission was off", false),
        NOT_SHOWN("fail", "Android refused to show it", false),
        REMOVED("removed", "Android removed it", false),
        RESTART("restart", "the app was closed by Android or crashed, and restarted", false),
    }

    interface Store {
        fun get(key: String): String?
        fun put(values: Map<String, String?>)
    }

    private var ride: String? = store.get(RIDE)
    private val counts: MutableMap<Why, Int> = Why.entries.associateWith { store.get(it.key)?.toIntOrNull() ?: 0 }.toMutableMap()
    /** The pop-up was up when the app last stopped running, for [ride]. */
    private val wasShowing = store.get(SHOWING) == "1"
    private var restartChecked = false

    /** The pop-up is up for [rideId]. The first show after the app restarted mid-ride counts as a restart. */
    fun shown(rideId: String) {
        startRide(rideId)
        store.put(mapOf(SHOWING to "1"))
    }

    /** The pop-up went away from [rideId] for [why]. */
    fun hid(rideId: String, why: Why) {
        startRide(rideId)
        counts[why] = (counts[why] ?: 0) + 1
        store.put(mapOf(why.key to counts[why].toString(), SHOWING to "0"))
    }

    /** A ride of [rideId] is on: a ride that was on with the pop-up up when the app stopped was cut by a restart. */
    fun riding(rideId: String) {
        if (restartChecked) return
        restartChecked = true
        if (ride == rideId && wasShowing) hid(rideId, Why.RESTART)
    }

    /** The ride ended: its summary (null when the pop-up never went away), and the trail starts over. */
    fun ended(): String? {
        val said = summary(counts)
        ride = null
        Why.entries.forEach { counts[it] = 0 }
        store.put(mapOf(RIDE to null, SHOWING to null) + Why.entries.associate { it.key to null })
        return said
    }

    val unexpected: Boolean get() = counts.any { (w, n) -> !w.expected && n > 0 }

    private fun startRide(rideId: String) {
        if (ride == rideId) return
        ride = rideId
        Why.entries.forEach { counts[it] = 0 }
        store.put(mapOf(RIDE to rideId, SHOWING to "0") + Why.entries.associate { it.key to null })
    }

    companion object {
        private const val RIDE = "ride"
        private const val SHOWING = "showing"

        fun summary(counts: Map<Why, Int>): String? {
            val total = counts.values.sum()
            if (total == 0) return null
            val parts = Why.entries.filter { (counts[it] ?: 0) > 0 }.joinToString("; ") { "${counts[it]}× ${it.label}" }
            return "The pop-up went away $total time${if (total == 1) "" else "s"} this ride: $parts"
        }

        fun prefs(context: Context): Store = object : Store {
            private val p = context.getSharedPreferences("hud_trail", Context.MODE_PRIVATE)
            override fun get(key: String): String? = p.getString(key, null)
            override fun put(values: Map<String, String?>) {
                val e = p.edit()
                values.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
                e.apply()
            }
        }
    }
}
