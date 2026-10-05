package com.ridetrack.app.ride

/**
 * A video that keeps going while things keep happening: started when a second event lands
 * inside an event clip, and held open until [holdUntil] passes. Each new event (and, later,
 * speech) pushes [holdUntil] further. Capped at [maxMillis]. Pure; the session films.
 */
class TriggeredVideo(
    private val maxMillis: Long = 10 * 60_000L,
    /** The video takes a moment to start; don't read "not running yet" as stopped. */
    private val graceMillis: Long = 8_000L,
) {
    enum class Action {
        NONE,
        /** Nothing more happened: stop filming. */
        STOP,
        /** It never started (camera not ready): fall back to a clip from the buffer. */
        FAILED,
    }

    var filming: Boolean = false
        private set
    private var startedAt = 0L
    private var seenRunning = false

    /** Keep filming until this time (the ride's clock). */
    var holdUntil: Long = 0L
        private set

    fun start(now: Long, holdUntil: Long) {
        filming = true
        startedAt = now
        seenRunning = false
        this.holdUntil = holdUntil
    }

    /** Something else happened: keep filming at least until [until]. */
    fun extend(until: Long) {
        if (filming && until > holdUntil) holdUntil = until
    }

    /** [running]: our video is being filmed (false once stopped by hand from the pop-up). */
    fun onTick(now: Long, running: Boolean): Action {
        if (!filming) return Action.NONE
        if (running) seenRunning = true
        if (!running && now - startedAt > graceMillis) {
            filming = false
            // Stopped by hand: leave it. Never ran: the caller saves a clip instead.
            return if (seenRunning) Action.NONE else Action.FAILED
        }
        if (now > holdUntil || now - startedAt >= maxMillis) {
            filming = false
            return Action.STOP
        }
        return Action.NONE
    }
}
