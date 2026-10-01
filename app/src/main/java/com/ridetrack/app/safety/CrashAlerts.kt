package com.ridetrack.app.safety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.ridetrack.app.R
import com.ridetrack.app.data.EmergencyContact
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.ride.RideSessionManager
import com.ridetrack.app.sensors.BatteryMonitor
import com.ridetrack.telemetry.math.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the crash alert is: counting down, sent, or cancelled by the rider. */
sealed interface CrashAlertState {
    val report: CrashReport
    val contacts: List<EmergencyContact>

    data class Countdown(override val report: CrashReport, override val contacts: List<EmergencyContact>, val deadlineMillis: Long) : CrashAlertState
    data class Sent(override val report: CrashReport, override val contacts: List<EmergencyContact>, val delivered: Map<String, Boolean>, val message: String, val sentAtMillis: Long, val allClearSent: Boolean = false) : CrashAlertState
    data class Cancelled(override val report: CrashReport, override val contacts: List<EmergencyContact>) : CrashAlertState
}

/**
 * After a suspected crash: a full-screen 30 s countdown with an alarm, then an SMS with the
 * location to the rider's emergency contacts, unless they say they're OK. The countdown
 * lives here (not in the screen), so it still sends if the screen never shows.
 */
class CrashAlerts(
    private val context: Context,
    private val settings: SettingsRepository,
    private val session: RideSessionManager,
    private val battery: BatteryMonitor,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CrashAlertState?>(null)
    val state: StateFlow<CrashAlertState?> = _state.asStateFlow()

    private var countdown: Job? = null
    private var followUp: Job? = null
    private var ringtone: Ringtone? = null

    fun start() {
        createChannel()
        scope.launch { session.crashes.collect { onCrash(it) } }
    }

    /** A crash was detected (or simulated from Profile in debug builds). */
    fun onCrash(report: CrashReport) {
        if (_state.value is CrashAlertState.Countdown) return
        scope.launch {
            val safety = settings.settings.first().safety
            val contacts = safety.contacts
            val withBattery = report.copy(
                batteryPercent = report.batteryPercent ?: battery.current()?.percent,
                riderName = report.riderName.ifBlank { safety.riderName },
                medical = if (report.medical.isEmpty) safety.medical else report.medical,
            )
            _state.value = CrashAlertState.Countdown(withBattery, contacts, System.currentTimeMillis() + COUNTDOWN_MILLIS)
            withContext(Dispatchers.Main) {
                startAlarm()
                showScreen()
            }
            countdown?.cancel()
            countdown = scope.launch {
                delay(COUNTDOWN_MILLIS)
                sendNow()
            }
        }
    }

    fun cancel() {
        val s = _state.value as? CrashAlertState.Countdown ?: return
        countdown?.cancel()
        Log.i(TAG, "Crash alert cancelled by the rider (false alarm)")
        _state.value = CrashAlertState.Cancelled(s.report, s.contacts)
        stopAlarm()
    }

    fun sendNow() {
        val s = _state.value as? CrashAlertState.Countdown ?: return
        countdown?.cancel()
        stopAlarm()
        val text = AlertMessage.crash(s.report)
        val delivered = s.contacts.associate { c -> c.phone to send(c.phone, text) }
        _state.value = CrashAlertState.Sent(s.report, s.contacts, delivered, text, System.currentTimeMillis())
        followUp?.cancel()
        followUp = scope.launch { followUpLocation(s) }
    }

    /** "I'm OK" after an alert went out. */
    fun allClear() {
        val s = _state.value as? CrashAlertState.Sent ?: return
        followUp?.cancel()
        val name = s.report.riderName
        s.contacts.forEach { send(it.phone, AlertMessage.allClear(name)) }
        _state.value = s.copy(allClearSent = true)
    }

    /** The screen was closed. */
    fun dismiss() {
        if (_state.value is CrashAlertState.Countdown) return
        _state.value = null
    }

    /** Profile's "Send test message". Returns how many went out. */
    suspend fun sendTest(): Int {
        val s = settings.settings.first().safety
        val frame = session.frame.value
        val report = CrashReport(
            timeMillis = System.currentTimeMillis(),
            latitude = frame?.latitude, longitude = frame?.longitude, accuracyM = frame?.gpsAccuracyM,
            speedBeforeMps = null, bikeName = session.active.value?.bikeName.orEmpty(), riderName = s.riderName,
            batteryPercent = battery.current()?.percent, medical = s.medical,
        )
        val text = AlertMessage.crash(report, test = true)
        return s.contacts.count { send(it.phone, text) }
    }

    fun canSendSms(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun send(phone: String, text: String): Boolean {
        if (!canSendSms()) {
            Log.w(TAG, "No SMS permission; alert not sent to $phone")
            return false
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService<SmsManager>() else @Suppress("DEPRECATION") SmsManager.getDefault()
            val manager = sms ?: return false
            manager.sendMultipartTextMessage(phone, null, manager.divideMessage(text), null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS to $phone failed", e)
            false
        }
    }

    /** If the rider is moved (ambulance, help), tell the contacts where to, once, within 2 min. */
    private suspend fun followUpLocation(s: CrashAlertState.Countdown) {
        val lat0 = s.report.latitude
        val lon0 = s.report.longitude
        val until = System.currentTimeMillis() + FOLLOW_UP_MILLIS
        while (System.currentTimeMillis() < until) {
            delay(15_000)
            val f = session.frame.value ?: continue
            val lat = f.latitude ?: continue
            val lon = f.longitude ?: continue
            val moved = lat0 == null || lon0 == null || Geo.distanceM(lat0, lon0, lat, lon) > FOLLOW_UP_METERS
            if (moved) {
                val text = AlertMessage.update(lat, lon, System.currentTimeMillis())
                s.contacts.forEach { send(it.phone, text) }
                return
            }
        }
    }

    private fun showScreen() {
        val intent = Intent(context, CrashAlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Straight to the screen when allowed (app open, or the HUD's draw-over permission)…
        runCatching { context.startActivity(intent) }
        // …and a full-screen notification in case it wasn't (locked phone, background).
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Possible crash: are you OK?")
            .setContentText("Your contacts get your location in 30 s unless you cancel.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setOngoing(true)
            .setTimeoutAfter(COUNTDOWN_MILLIS + 5_000)
            .build()
        runCatching { context.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, n) }
    }

    private fun startAlarm() {
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.getOrNull()
        val pattern = longArrayOf(0, 600, 400)
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun stopAlarm() {
        runCatching { ringtone?.stop() }
        ringtone = null
        vibrator()?.cancel()
        runCatching { context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID) }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService<VibratorManager>()?.defaultVibrator else context.getSystemService()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Crash alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks if you're OK after a suspected crash"
            setBypassDnd(true)
        }
        context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CrashAlerts"
        private const val CHANNEL_ID = "crash_alert"
        private const val NOTIFICATION_ID = 4242
        const val COUNTDOWN_MILLIS = 30_000L
        private const val FOLLOW_UP_MILLIS = 2 * 60_000L
        private const val FOLLOW_UP_METERS = 100.0
    }
}
