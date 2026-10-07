package com.ridetrack.app.moments

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.RollingBuffer
import com.ridetrack.telemetry.moments.TwoMics
import com.ridetrack.telemetry.moments.VoiceBand
import com.ridetrack.telemetry.moments.VoiceWindows

/**
 * Microphone → AAC into the [RollingBuffer]. Runs its own thread until [release].
 * [device] picks an external mic (USB-C receiver, Bluetooth, wired); null = the phone's.
 *
 * Only a Bluetooth headset needs the phone's call-audio link (and while it's open the
 * headset can't play music). It's opened here, and closed on release or as soon as the
 * headset drops it: [onHeadsetLost] then tells the owner to carry on with another mic.
 * Any other mic never touches the call link or the headset.
 */
class AudioEncoder(
    private val context: Context,
    private val buffer: RollingBuffer,
    private val device: AudioDeviceInfo? = null,
    private val onHeadsetLost: () -> Unit = {},
    /** Each chunk: (wall time, dBFS in the voice range, chunk length in ms, voice probability 0..1). */
    private val onLevel: ((Long, Float, Long, Float) -> Unit)? = null,
    /** Silero, for "Film when I speak"; null = no voice check (probability reads 1). */
    private val vad: SileroVad? = null,
    /**
     * Records both channels of a two-transmitter receiver: the voice side goes on as usual,
     * the other (the engine mic) into the buffer's second track. False = one mic, mono.
     */
    twoMics: Boolean = false,
    /** The voice mic is on the right channel (the rider swapped them in the mic test). */
    private val swap: Boolean = false,
    /** Each chunk with two mics: (left dBFS, right dBFS). */
    private val onChannels: ((Float, Float) -> Unit)? = null,
) {
    private val windows = VoiceWindows(SAMPLE_RATE)
    private var lastVoice = 0f
    private val voiceBand = VoiceBand(SAMPLE_RATE)
    private val audioManager = context.getSystemService<AudioManager>()
    private val bluetooth = device != null && Microphones.typeOf(device) == MicType.BLUETOOTH
    @Volatile private var scoStarted = false
    private val startNanos = System.nanoTime()

    @Volatile
    var format: MediaFormat? = null
        private set

    @Volatile
    private var running = true
    private val thread = Thread(::loop, "moments-audio")

    // Bluetooth mics only arrive on the voice path; everything else films like a camcorder.
    private val sourceType = if (bluetooth) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.CAMCORDER

    // RECORD_AUDIO is checked by the caller before constructing this.
    @SuppressLint("MissingPermission")
    private fun open(channels: Int): AudioRecord = AudioRecord(
        sourceType,
        SAMPLE_RATE,
        channels,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, channels, AudioFormat.ENCODING_PCM_16BIT), 8192) * 2,
    )

    /** Stereo when two mics are wanted and the input allows it; mono otherwise. */
    private val record: AudioRecord = if (twoMics && !bluetooth) {
        runCatching { open(AudioFormat.CHANNEL_IN_STEREO).takeIf { it.state == AudioRecord.STATE_INITIALIZED && it.channelCount == 2 } }
            .getOrNull() ?: open(AudioFormat.CHANNEL_IN_MONO)
    } else {
        open(AudioFormat.CHANNEL_IN_MONO)
    }
    private val stereo = record.channelCount == 2
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    /** The engine side's encoder (two mics only). */
    private val engineCodec: MediaCodec? = if (stereo) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) else null
    private val twoMicCheck = TwoMics()

    /** Recording both channels of the receiver. */
    val recordingTwo: Boolean get() = stereo

    /** Enough sound heard to tell one mic from two. */
    val twoKnown: Boolean get() = stereo && twoMicCheck.known

    /** The two channels are really two different mics (so the engine track is worth keeping). */
    val twoDifferent: Boolean get() = stereo && twoMicCheck.differ

    @Volatile
    var engineFormat: MediaFormat? = null
        private set

    init {
        fun aac() = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(aac(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        engineCodec?.configure(aac(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        engineCodec?.start()
        if (bluetooth) openBluetoothRoute()
        if (device != null) record.setPreferredDevice(device)
        if (bluetooth) {
            record.addOnRoutingChangedListener({ r ->
                val d = r.routedDevice
                // The headset left call mode: let it go at once. Holding the request keeps it
                // bouncing in and out of call mode, so its music stays silent.
                if (scoStarted && System.nanoTime() - startNanos > SETTLE_NANOS && (d == null || Microphones.typeOf(d) != MicType.BLUETOOTH)) {
                    closeBluetoothRoute()
                    onHeadsetLost()
                }
            }, null)
        }
        record.startRecording()
        thread.start()
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        // Wall clock = monotonic + a fixed offset measured once.
        val offsetMicros = System.currentTimeMillis() * 1000 - System.nanoTime() / 1000
        try {
            while (running) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex) ?: continue
                    inBuf.clear()
                    val read = if (stereo) readStereo(inBuf) else record.read(inBuf, minOf(inBuf.capacity(), 4096))
                    val nowMicros = System.nanoTime() / 1000
                    if (read > 0) onLevel?.let { report ->
                        val samples = read / 2
                        report((nowMicros + offsetMicros) / 1000, levelDb(inBuf, samples), samples * 1000L / SAMPLE_RATE, voice(inBuf, samples))
                    }
                    // pts = when the first sample of this chunk was captured.
                    val pts = nowMicros - (read.coerceAtLeast(0) / 2) * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inIndex, 0, read.coerceAtLeast(0), pts, 0)
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        format = codec.outputFormat
                        continue
                    }
                    if (outIndex < 0) break
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        codec.getOutputBuffer(outIndex)?.let { out ->
                            val bytes = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(bytes, 0, info.size)
                            buffer.addAudio(EncodedSample(info.presentationTimeUs + offsetMicros, true, bytes))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                engineCodec?.let { drainEngine(it, info, offsetMicros) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture stopped", e)
        }
    }

    private val pcm = ShortArray(FRAMES * 2)
    private val left = ShortArray(FRAMES)
    private val right = ShortArray(FRAMES)

    /**
     * Reads a stereo chunk: the voice side into [voiceBuf] (as the mono path would), the engine
     * side into its own encoder. Returns the bytes put in [voiceBuf].
     */
    private fun readStereo(voiceBuf: java.nio.ByteBuffer): Int {
        val frames = minOf(FRAMES, voiceBuf.capacity() / 2)
        val got = record.read(pcm, 0, frames * 2)
        if (got <= 0) return got
        val n = got / 2
        val (lDb, rDb) = twoMicCheck.split(pcm, n, left, right, SAMPLE_RATE)
        onChannels?.invoke(lDb, rDb)
        val voice = if (swap) right else left
        val engine = if (swap) left else right
        val vb = voiceBuf.order(java.nio.ByteOrder.nativeOrder())
        for (i in 0 until n) vb.putShort(voice[i])
        vb.flip()
        val pts = System.nanoTime() / 1000 - n * 1_000_000L / SAMPLE_RATE
        engineCodec?.let { ec ->
            val idx = ec.dequeueInputBuffer(5_000)
            if (idx >= 0) {
                val eb = ec.getInputBuffer(idx)?.apply { clear(); order(java.nio.ByteOrder.nativeOrder()) }
                if (eb != null) {
                    val m = minOf(n, eb.capacity() / 2)
                    for (i in 0 until m) eb.putShort(engine[i])
                    ec.queueInputBuffer(idx, 0, m * 2, pts, 0)
                }
            }
        }
        return n * 2
    }

    private fun drainEngine(ec: MediaCodec, info: MediaCodec.BufferInfo, offsetMicros: Long) {
        while (true) {
            val out = ec.dequeueOutputBuffer(info, 0)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { engineFormat = ec.outputFormat; continue }
            if (out < 0) break
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
                ec.getOutputBuffer(out)?.let { b ->
                    val bytes = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(bytes, 0, info.size)
                    buffer.addExtra(EncodedSample(info.presentationTimeUs + offsetMicros, true, bytes))
                }
            }
            ec.releaseOutputBuffer(out, false)
        }
    }

    /** The highest voice probability among the 32 ms windows this chunk completed. */
    private fun voice(buf: java.nio.ByteBuffer, samples: Int): Float {
        val v = vad ?: return 1f
        val b = buf.duplicate().order(java.nio.ByteOrder.nativeOrder())
        var best = -1f
        windows.feed(samples, { i -> b.getShort(i * 2) / 32768.0 }) { w -> best = maxOf(best, v.probability(w)) }
        // A chunk too short to finish a window keeps the last reading.
        if (best >= 0f) lastVoice = best
        return lastVoice
    }

    /** Loudness of [samples] 16-bit samples at the start of [buf], in the voice range (dBFS). */
    private fun levelDb(buf: java.nio.ByteBuffer, samples: Int): Float {
        val b = buf.duplicate().order(java.nio.ByteOrder.nativeOrder())
        return voiceBand.levelDb(samples) { i -> b.getShort(i * 2) / 32768.0 }
    }

    /** The input actually in use (may differ from the preferred one if it disconnected). */
    val routedDevice: AudioDeviceInfo? get() = runCatching { record.routedDevice }.getOrNull()

    fun addOnRoutingChanged(listener: (AudioDeviceInfo?) -> Unit) {
        record.addOnRoutingChangedListener({ r -> listener(r.routedDevice) }, null)
    }

    @Suppress("DEPRECATION")
    private fun openBluetoothRoute() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (target != null) scoStarted = am.setCommunicationDevice(target)
            } else {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoStarted = true
            }
        }.onFailure { Log.w(TAG, "Bluetooth mic route unavailable", it) }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun closeBluetoothRoute() {
        if (!scoStarted) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
        }
        scoStarted = false
    }

    fun release() {
        running = false
        runCatching { thread.join(500) }
        runCatching { record.stop() }
        record.release()
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { engineCodec?.stop() }
        runCatching { engineCodec?.release() }
        closeBluetoothRoute()
    }

    companion object {
        private const val TAG = "MomentsAudio"
        private const val SAMPLE_RATE = 44_100
        /** Stereo frames read per chunk (as much as the mono path reads: 2048 samples). */
        private const val FRAMES = 2048
        /** The call link takes a moment to come up; routing flips during it don't count as a drop. */
        private const val SETTLE_NANOS = 4_000_000_000L
    }
}
