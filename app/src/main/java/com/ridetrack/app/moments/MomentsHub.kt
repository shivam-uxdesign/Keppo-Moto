package com.ridetrack.app.moments

import com.ridetrack.telemetry.model.RideEventType

import android.graphics.Bitmap
import com.ridetrack.telemetry.moments.MomentWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MomentRequest {
    val rideId: String

    data class Clip(override val rideId: String, val window: MomentWindow) : MomentRequest

    data class Photo(
        override val rideId: String,
        val timeMillis: Long,
        val latitude: Double?,
        val longitude: Double?,
        val speedMps: Double?,
    ) : MomentRequest

    /** Start filming a long video (yours, or because GPS dropped), from [preRollMillis] ago. */
    data class StartLive(
        override val rideId: String,
        val source: MomentSource,
        val preRollMillis: Long,
        val latitude: Double?,
        val longitude: Double?,
        val speedMps: Double?,
        /** A chain of events filmed as one video: its events so far, strongest value, first event. */
        val types: Set<RideEventType> = emptySet(),
        val peakValue: Double? = null,
        val anchorMillis: Long? = null,
    ) : MomentRequest

    /** More events joined the chain being filmed. */
    data class LiveEvents(override val rideId: String, val types: Set<RideEventType>, val peakValue: Double?) : MomentRequest

    data class LiveControl(override val rideId: String, val action: LiveAction) : MomentRequest
}

enum class LiveAction { PAUSE, RESUME, STOP }

/** A video being filmed on purpose, as the HUD shows it. */
data class LiveState(
    val source: MomentSource,
    /** Camera getting ready; nothing is being written yet. */
    val starting: Boolean = false,
    val paused: Boolean = false,
    /** Filmed before the current stretch (pauses don't count). */
    val recordedMillis: Long = 0,
    /** When the current stretch began; null while paused or starting. */
    val segmentStartMillis: Long? = null,
) {
    fun elapsedMillis(now: Long): Long = recordedMillis + (segmentStartMillis?.let { now - it } ?: 0L)
}

/** A video that was just saved: its length, for the HUD's "Saved to moments" note. */
data class LiveSaved(val source: MomentSource, val lengthMillis: Long, val atMillis: Long)

enum class MomentStatus { OFF, STARTING, ARMED, PAUSED }

enum class PauseReason(val message: String) {
    RIDE_PAUSED("Paused while stopped"),
    HOT("Paused: phone is hot"),
    LOW_STORAGE("Paused: storage almost full"),
    CAMERA_BUSY("Camera in use by another app. Retrying"),
    NO_PERMISSION("Camera permission is off"),
    FAILED("Camera unavailable"),
}

data class MomentState(
    val status: MomentStatus = MomentStatus.OFF,
    val reason: PauseReason? = null,
    /** An event is being filmed (its after-window is running) or a clip is being written. */
    val saving: Boolean = false,
    val saved: Int = 0,
    /** Start (with look-back) of the event clip being filmed; the HUD counts from here. */
    val clipStartMillis: Long? = null,
    /** A periodic photo is coming at this time (the HUD counts 3 · 2 · 1). */
    val photoAtMillis: Long? = null,
    /** When the last photo was taken (the HUD says "photo" briefly). */
    val photoTakenAtMillis: Long? = null,
    /** The chosen mic (its name) is missing, so the phone mic is recording; null when all's well. */
    val micFallback: String? = null,
    /** The kind of mic recording now; null while no mic is open. */
    val micType: MicType? = null,
)

/**
 * Connects the ride session (which decides *when* to capture) with the recorder in the
 * foreground service (which owns the camera). Requests made before the recorder is up,
 * or after it went away, are queued and handed over when it attaches.
 */
class MomentsHub {
    private val _state = MutableStateFlow(MomentState())
    val state: StateFlow<MomentState> = _state.asStateFlow()

    private val _live = MutableStateFlow<LiveState?>(null)
    val live: StateFlow<LiveState?> = _live.asStateFlow()

    private val _liveSaved = MutableStateFlow<LiveSaved?>(null)
    val liveSaved: StateFlow<LiveSaved?> = _liveSaved.asStateFlow()

    /** Small camera frames while you film on purpose (the HUD's viewfinder). */
    private val _viewfinder = MutableStateFlow<Bitmap?>(null)
    val viewfinder: StateFlow<Bitmap?> = _viewfinder.asStateFlow()

    private val eventPending = MutableStateFlow(false)
    private var writing = false

    private val queue = ArrayList<MomentRequest>()
    private var sink: ((MomentRequest) -> Unit)? = null

    @Synchronized
    fun submit(r: MomentRequest) {
        val s = sink
        if (s != null) s(r) else queue += r
    }

    /** Debug: save the last 10 s and the next 5 s now, without waiting for an event. */
    fun requestTestClip(rideId: String) {
        val now = System.currentTimeMillis()
        submit(MomentRequest.Clip(rideId, MomentWindow(now - 10_000, now + 5_000, now, emptySet(), null, null, null, null)))
    }

    /** Live-video controls go straight to the recorder; without one (no ride) they're dropped. */
    @Synchronized
    fun submitLive(r: MomentRequest): Boolean {
        val s = sink ?: return false
        s(r)
        return true
    }

    fun setLive(state: LiveState?) {
        _live.value = state
    }

    fun onLiveSaved(saved: LiveSaved) {
        _liveSaved.value = saved
    }

    fun setViewfinder(frame: Bitmap?) {
        _viewfinder.value = frame
    }

    @Synchronized
    fun attach(s: (MomentRequest) -> Unit) {
        sink = s
        queue.forEach(s)
        queue.clear()
    }

    @Synchronized
    fun detach() {
        sink = null
    }

    /** Anything not handed to a recorder yet (e.g. the ride ended before it started). */
    @Synchronized
    fun drainQueued(): List<MomentRequest> = queue.toList().also { queue.clear() }

    fun setEventPending(pending: Boolean, clipStartMillis: Long? = null) {
        eventPending.value = pending
        if (pending && clipStartMillis != null) clipStart = clipStartMillis
        publish()
    }

    fun setPhotoCountdown(atMillis: Long?) {
        _state.value = _state.value.copy(photoAtMillis = atMillis)
    }

    fun onPhotoTaken(atMillis: Long) {
        _state.value = _state.value.copy(photoAtMillis = null, photoTakenAtMillis = atMillis)
    }

    fun setWriting(w: Boolean) {
        writing = w
        publish()
    }

    fun setStatus(status: MomentStatus, reason: PauseReason? = null) {
        _state.value = _state.value.copy(status = status, reason = reason)
        publish()
    }

    private val _micLevel = MutableStateFlow<Float?>(null)
    /** Mic loudness in dBFS (null while no mic is open), for the HUD's meter. */
    val micLevel: StateFlow<Float?> = _micLevel.asStateFlow()
    private val levels = ArrayList<Triple<Long, Float, Long>>()

    /** From the audio thread: one chunk's level. The session drains them for speech detection. */
    fun reportLevel(timeMillis: Long, levelDb: Float, chunkMillis: Long) {
        _micLevel.value = levelDb
        synchronized(levels) {
            levels += Triple(timeMillis, levelDb, chunkMillis)
            if (levels.size > MAX_LEVELS) levels.subList(0, levels.size - MAX_LEVELS).clear()
        }
    }

    fun drainLevels(): List<Triple<Long, Float, Long>> = synchronized(levels) { levels.toList().also { levels.clear() } }

    fun clearLevel() {
        _micLevel.value = null
        synchronized(levels) { levels.clear() }
    }

    private val _speaking = MutableStateFlow(false)
    /** "Start filming when I speak" hears you now (the HUD's meter lights up). */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    fun setSpeaking(on: Boolean) {
        _speaking.value = on
    }

    fun setMicType(type: MicType?) {
        if (_state.value.micType != type) _state.value = _state.value.copy(micType = type)
    }

    fun setMicFallback(name: String?) {
        if (_state.value.micFallback != name) _state.value = _state.value.copy(micFallback = name)
    }

    /** Set by the recorder: "Reconnect mic" on the pop-up. */
    @Volatile
    var onReconnectMic: (() -> Unit)? = null

    fun reconnectMic() {
        onReconnectMic?.invoke()
    }

    fun onSaved() {
        _state.value = _state.value.copy(saved = _state.value.saved + 1)
    }

    fun reset() {
        eventPending.value = false
        writing = false
        clipStart = null
        _state.value = MomentState()
        clearLevel()
        _speaking.value = false
        _live.value = null
        _viewfinder.value = null
    }

    private fun publish() {
        val s = _state.value
        val saving = s.status == MomentStatus.ARMED && (eventPending.value || writing)
        if (!saving) clipStart = null
        _state.value = s.copy(saving = saving, clipStartMillis = clipStart)
    }

    /** Kept until the clip is written, so the HUD keeps counting through the save. */
    private var clipStart: Long? = null

    private companion object {
        /** ~5 s of chunks: more than a tick ever needs. */
        const val MAX_LEVELS = 120
    }
}
