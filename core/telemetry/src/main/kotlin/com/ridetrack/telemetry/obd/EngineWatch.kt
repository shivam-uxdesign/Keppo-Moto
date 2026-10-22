package com.ridetrack.telemetry.obd

/**
 * Whether the engine runs, from what the OBD adapter tells: its voltage (the battery rests at
 * 12.4–12.8 V; with the engine running the alternator charges at 13.5–14.6 V; measured on the
 * rider's Pulsar: 12.8 key on, 14.6 running) and RPM when the bike reports it. An adapter that
 * only has power with the key on drops off Bluetooth the moment the key goes off. Pure.
 */
class EngineWatch(
    private val runningVolts: Double = RUNNING_VOLTS,
    private val holdMillis: Long = 3_000L,
) {
    enum class State {
        /** No link to the adapter (out of reach, or the key is off). */
        OFF,
        /** Key on, engine not running. */
        KEY_ON,
        RUNNING,
    }

    var state: State = State.OFF
        private set
    private var chargingSince: Long? = null

    /** The link came up or went down. Down = key off (or out of reach). */
    fun onLink(connected: Boolean): State {
        if (!connected) {
            state = State.OFF
            chargingSince = null
        } else if (state == State.OFF) {
            state = State.KEY_ON
        }
        return state
    }

    /** A reading at [timeMillis]: the adapter's [volts] and/or the engine's [rpm] (null when not read). */
    fun onReading(timeMillis: Long, volts: Double?, rpm: Double?): State {
        if (state == State.OFF) state = State.KEY_ON
        // RPM says it at once; voltage needs to hold a moment (cranking makes it jump about).
        if (rpm != null) {
            state = if (rpm >= RUNNING_RPM) State.RUNNING else State.KEY_ON
            chargingSince = if (state == State.RUNNING) chargingSince ?: timeMillis else null
            return state
        }
        if (volts != null) {
            if (volts >= runningVolts) {
                val since = chargingSince ?: timeMillis.also { chargingSince = it }
                if (timeMillis - since >= holdMillis) state = State.RUNNING
            } else if (volts < runningVolts - HYSTERESIS) {
                chargingSince = null
                state = State.KEY_ON
            }
        }
        return state
    }

    companion object {
        /** Above this the alternator is charging: the engine runs. */
        const val RUNNING_VOLTS = 13.2
        /** It must fall this far below to count as stopped (voltage wobbles at idle). */
        const val HYSTERESIS = 0.4
        /** Idle is 1,000–1,500 rpm; anything over this is running. */
        const val RUNNING_RPM = 400.0
    }
}
