package com.ridetrack.app.moments

import com.ridetrack.telemetry.moments.BackgroundLevel

import com.ridetrack.telemetry.model.RideEventType

import android.graphics.Bitmap
import com.ridetrack.telemetry.moments.MomentWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

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

enum class LiveAction { PAUSE, RESUME, STOP, FLIP }

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
    /** Filming with the back camera (road view). */
    val back: Boolean = false,
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

    private val _liveFailed = MutableStateFlow<Long?>(null)
    /** When a video was asked for but the camera didn't start (for a note on the HUD). */
    val liveFailed: StateFlow<Long?> = _liveFailed.asStateFlow()

    fun reportLiveFailed() {
        _liveFailed.value = System.currentTimeMillis()
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

    private val _micLift = MutableStateFlow<Float?>(null)
    /** With two mics: how much the helmet mic stands out from the engine mic now (dB); null with one mic. */
    val micLift: StateFlow<Float?> = _micLift.asStateFlow()

    private val _twoMics = MutableStateFlow<TwoMicLevels?>(null)
    /** Both mics' loudness while a two-channel receiver records (null otherwise). */
    val twoMics: StateFlow<TwoMicLevels?> = _twoMics.asStateFlow()

    /** From the audio thread: each mic's loudness (dBFS) and what's known about them. */
    fun reportTwo(voiceDb: Float, engineDb: Float, kind: TwoMicKind) {
        _twoMics.value = TwoMicLevels(voiceDb, engineDb, kind)
        // The helmet mic against the engine mic says who's talking; only once they're known to be two
        // different mics (one mic on both sides, mixed or as a backup copy, only differs in noise).
        val l = if (kind == TwoMicKind.TWO) twoMicLift.onLevels(System.currentTimeMillis(), voiceDb, engineDb) else null
        lift = if (_voiceOnEngineSide.value) null else l
    }

    private val twoMicLift = com.ridetrack.telemetry.moments.TwoMicLift()
    @Volatile private var lift: Float? = null

    /** The engine side's voice probability, from the second voice detector (two mics). */
    @Volatile private var engineVoice: Float? = null
    fun reportEngineVoice(p: Float) {
        engineVoice = p
    }

    // The voice's crisp part (1–4 kHz), where wind is weakest: above its own background, and the
    // helmet mic against the engine mic there. Logged with each mic line (peaks since the last).
    private val highBackground = BackgroundLevel(8_000L)
    private val highLift = com.ridetrack.telemetry.moments.HighBandLift()
    /** The latest crisp-band lift (two mics), going into the next chunk for the speech check. */
    @Volatile private var latestHighLift: Float? = null
    private val _micHighLift = MutableStateFlow<Float?>(null)
    /** How far the helmet mic stands out in the crisp band (dB), for the HUD meter. */
    val micHighLift: StateFlow<Float?> = _micHighLift.asStateFlow()
    private var highPeak: Float? = null
    private var highLiftPeak: Float? = null
    fun reportHigh(timeMillis: Long, voiceDb: Float, engineDb: Float?) = synchronized(highBackground) {
        val bg = if (_speaking.value) highBackground.current else highBackground.onLevel(timeMillis, voiceDb)
        highPeak = maxOf(highPeak ?: -99f, voiceDb - bg)
        latestHighLift = engineDb?.let { e -> highLift.onLevels(timeMillis, voiceDb, e).also { highLiftPeak = maxOf(highLiftPeak ?: -99f, it) } }
    }

    /** " · high +6 dB · high lift +4 dB" since the last call, for the mic line; empty before any audio. */
    fun highSummary(): String = synchronized(highBackground) {
        val out = (highPeak?.let { " · high +${it.roundToInt()} dB" } ?: "") + (highLiftPeak?.let { " · high lift +${it.roundToInt()} dB" } ?: "")
        highPeak = null; highLiftPeak = null
        out
    }

    /** The crisp-band lift that counts as talking, from the rider's sensitivity (set each ride tick). */
    @Volatile var highMinDb: Float = com.ridetrack.telemetry.moments.HighBandLift.SPEECH_DB

    private val _voiceLike = MutableStateFlow(true)
    /** The latest chunk passed the voice detector (the HUD says when loudness alone isn't enough). */
    val voiceLike: StateFlow<Boolean> = _voiceLike.asStateFlow()

    private val roleCheck = com.ridetrack.telemetry.moments.MicRoleCheck()
    private val _voiceOnEngineSide = MutableStateFlow(false)
    /**
     * The voice keeps being heard on the side set as Engine and not on the Voice side: the mics
     * are set the wrong way round (Settings › Moments › Two mics › Swap). The app doesn't swap them
     * itself; meanwhile the helmet-against-engine comparison is off (it would read revs as talking).
     */
    val voiceOnEngineSide: StateFlow<Boolean> = _voiceOnEngineSide.asStateFlow()

    private val _liftDistrusted = MutableStateFlow(0L)
    /** When the "helmet mic stands out" rule last kept a video going with no voice in it (0 = never). */
    val liftDistrusted: StateFlow<Long> = _liftDistrusted.asStateFlow()
    fun reportLiftDistrusted(timeMillis: Long) {
        _liftDistrusted.value = timeMillis
    }

    private val _wind = MutableStateFlow(0f)
    /** How windy the mic is now: 0 calm .. 1 heavy wind (from the background level), for the HUD's mark. */
    val wind: StateFlow<Float> = _wind.asStateFlow()

    private val _micLevel = MutableStateFlow<Float?>(null)
    /** Mic loudness in the voice range, in dB above the background noise (null while no mic is open). */
    val micLevel: StateFlow<Float?> = _micLevel.asStateFlow()
    /** The wind/engine background: over 8 s, and not learned while you talk (a long shout would become "background"). */
    private val background = BackgroundLevel(8_000L)
    private val clipWatch = com.ridetrack.telemetry.moments.ClipWatch()
    private val _micOverloaded = MutableStateFlow(false)
    /** The mic is overloaded (clipping) by wind: a shout can't get louder than it. */
    val micOverloaded: StateFlow<Boolean> = _micOverloaded.asStateFlow()

    /** From the audio thread: how many of a chunk's samples hit full scale. */
    fun reportClipping(timeMillis: Long, clipped: Int, samples: Int) {
        val over = synchronized(clipWatch) { clipWatch.onChunk(timeMillis, clipped, samples) }
        if (over != _micOverloaded.value) {
            _micOverloaded.value = over
            if (over) log("mic overloaded by wind: ${"%.1f".format(java.util.Locale.US, clipped * 100.0 / samples.coerceAtLeast(1))}% of samples at full scale; speech is hard to hear over it")
        }
    }
    private val levels = ArrayList<VoiceChunk>()
    private val engine = ArrayList<Triple<Long, Float, Long>>()

    /** The engine mic's level (two-mic recording): (time, dBFS, chunk ms), for revs and exhaust pops. */
    fun reportEngine(timeMillis: Long, levelDb: Float, chunkMillis: Long) = synchronized(engine) {
        engine += Triple(timeMillis, levelDb, chunkMillis)
        if (engine.size > MAX_LEVELS * 2) engine.subList(0, engine.size - MAX_LEVELS * 2).clear()
    }

    fun drainEngine(): List<Triple<Long, Float, Long>> = synchronized(engine) { engine.toList().also { engine.clear() } }

    /** From the audio thread: one chunk's level. The session drains them for speech detection. */
    fun reportLevel(timeMillis: Long, levelDb: Float, chunkMillis: Long, voice: Float) {
        val chunk = synchronized(levels) {
            val bg = if (_speaking.value) background.current else background.onLevel(timeMillis, levelDb)
            VoiceChunk(timeMillis, levelDb, bg, chunkMillis, voice, lift, latestHighLift.takeIf { !_voiceOnEngineSide.value }).also {
                levels += it
                if (levels.size > MAX_LEVELS) levels.subList(0, levels.size - MAX_LEVELS).clear()
            }
        }
        engineVoice?.let { ev ->
            val swapped = synchronized(roleCheck) { roleCheck.onChunk(chunkMillis, voice, ev) } == com.ridetrack.telemetry.moments.MicRoleCheck.Verdict.SWAPPED
            if (swapped && !_voiceOnEngineSide.value) {
                _voiceOnEngineSide.value = true
                lift = null
                log("two mics: the voice is heard on Mic 2 (set as Engine), not on Mic 1: the mics look swapped. Helmet-vs-engine check off for this ride; swap them in Settings › Moments › Two mics")
            }
        }
        _wind.value = com.ridetrack.telemetry.moments.VoiceInWind.wind(chunk.backgroundDb)
        _voiceLike.value = voice >= com.ridetrack.telemetry.moments.VoiceInWind.voiceMin(_wind.value) ||
            (chunk.highLiftDb ?: -99f) >= highMinDb
        _micHighLift.value = chunk.highLiftDb
        _micLift.value = chunk.liftDb
        _micLevel.value = chunk.aboveDb
    }

    fun drainLevels(): List<VoiceChunk> = synchronized(levels) { levels.toList().also { levels.clear() } }

    /** The voice trace (tester builds; set by the recorder while a ride runs), and what it needs from the session. */
    @Volatile var trace: VoiceTrace? = null
    @Volatile var speedKmh: Float? = null
    /** The rider's sensitivity, and whether the helmet-vs-engine rule is allowed now (set each ride tick), for the trace's rules column. */
    @Volatile var marginDb: Float = 0f
    @Volatile var liftMinDb: Float = com.ridetrack.telemetry.moments.VoiceInWind.LIFT_DB
    @Volatile var liftAllowed: Boolean = true

    /** From the audio thread, after the chunk's other reports: one row of the voice trace. */
    fun reportTrace(a: TraceAudio) {
        val t = trace ?: return
        val chunk = synchronized(levels) { levels.lastOrNull() } ?: lastTraced
        lastTraced = chunk
        val rules = chunk?.let { c ->
            val wind = com.ridetrack.telemetry.moments.VoiceInWind.wind(c.backgroundDb)
            com.ridetrack.telemetry.moments.SpeechGate.rules(
                c.aboveDb, com.ridetrack.telemetry.moments.VoiceInWind.margin(marginDb, wind), c.voice,
                com.ridetrack.telemetry.moments.VoiceInWind.voiceMin(wind), c.liftDb?.takeIf { liftAllowed }, c.highLiftDb, liftMinDb, highMinDb,
            ).joinToString("+") { it.key }
        }.orEmpty()
        t.row(a, chunk, speedKmh, _wind.value, engineVoice, _twoMics.value?.kind, _speaking.value, rules)
    }
    @Volatile private var lastTraced: VoiceChunk? = null

    fun clearLevel() {
        _micLevel.value = null
        _micHighLift.value = null
        latestHighLift = null
        synchronized(highBackground) { highLift.reset() }
        _micLift.value = null
        _wind.value = 0f
        lift = null
        twoMicLift.reset()
        engineVoice = null
        _twoMics.value = null
        synchronized(levels) {
            levels.clear()
            background.reset()
        }
    }

    /** Writes a line to the moments log (set by the recorder while a ride runs). */
    var onLog: ((String) -> Unit)? = null
    fun log(message: String) {
        onLog?.invoke(message)
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
        synchronized(roleCheck) { roleCheck.reset() }
        _voiceOnEngineSide.value = false
        _liftDistrusted.value = 0L
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

/** Whether a two-channel receiver really carries two mics. */
enum class TwoMicKind {
    /** Not enough sound yet to tell. */
    LISTENING,
    /** Two different mics: the engine mic is kept as its own sound. */
    TWO,
    /** Both sides carry the same mic for a while: the receiver is on Mono or Safety Track. Labels stay Voice and Engine. */
    MONO,
}

/** Each mic's loudness in dBFS (-90 silent, 0 loudest), as the HUD and settings meters show it. */
data class TwoMicLevels(val voiceDb: Float, val engineDb: Float, val kind: TwoMicKind)

/** One audio chunk: its level in the voice range and the background then (dBFS), and how voice-like it is (0..1). */
/**
 * One audio chunk: its level in the voice range and the background then (dBFS), how voice-like
 * it is (0..1), and with two mics how much the helmet mic stands out from the engine mic ([liftDb]).
 */
data class VoiceChunk(val timeMillis: Long, val levelDb: Float, val backgroundDb: Float, val chunkMillis: Long, val voice: Float = 1f, val liftDb: Float? = null, val highLiftDb: Float? = null) {
    val aboveDb: Float get() = levelDb - backgroundDb
}
