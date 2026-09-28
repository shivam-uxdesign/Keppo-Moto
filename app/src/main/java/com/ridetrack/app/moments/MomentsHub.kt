package com.ridetrack.app.moments

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
}

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
)

/**
 * Connects the ride session (which decides *when* to capture) with the recorder in the
 * foreground service (which owns the camera). Requests made before the recorder is up,
 * or after it went away, are queued and handed over when it attaches.
 */
class MomentsHub {
    private val _state = MutableStateFlow(MomentState())
    val state: StateFlow<MomentState> = _state.asStateFlow()

    private val eventPending = MutableStateFlow(false)
    private var writing = false

    private val queue = ArrayList<MomentRequest>()
    private var sink: ((MomentRequest) -> Unit)? = null

    @Synchronized
    fun submit(r: MomentRequest) {
        val s = sink
        if (s != null) s(r) else queue += r
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

    fun setEventPending(pending: Boolean) {
        eventPending.value = pending
        publish()
    }

    fun setWriting(w: Boolean) {
        writing = w
        publish()
    }

    fun setStatus(status: MomentStatus, reason: PauseReason? = null) {
        _state.value = _state.value.copy(status = status, reason = reason)
        publish()
    }

    fun onSaved() {
        _state.value = _state.value.copy(saved = _state.value.saved + 1)
    }

    fun reset() {
        eventPending.value = false
        writing = false
        _state.value = MomentState()
    }

    private fun publish() {
        val s = _state.value
        _state.value = s.copy(saving = s.status == MomentStatus.ARMED && (eventPending.value || writing))
    }
}
