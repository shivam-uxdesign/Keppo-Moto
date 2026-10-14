package com.ridetrack.app.safety

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the crash alert is: counting down, sent, or cancelled by the rider. */
sealed interface CrashAlertState {
    val report: CrashReport
    val contacts: List<EmergencyContact>

    data class Countdown(override val report: CrashReport, override val contacts: List<EmergencyContact>, val deadlineMillis: Long) : CrashAlertState
    data class Sent(
        override val report: CrashReport,
        override val contacts: List<EmergencyContact>,
        val delivered: Map<String, SmsStatus>,
        val message: String,
        val sentAtMillis: Long,
        val allClearSent: Boolean = false,
        /** Some texts haven't gone yet (no signal): they're tried again every 30 s. */
        val retrying: Boolean = false,
    ) : CrashAlertState
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
    private val sms = SmsSender(context)

    /** Alert texts can be sent (SMS allowed). */
    fun canText(): Boolean = sms.canSend()
    private var nagging: Job? = null
    /** Texts being tried again after failing (no signal at the crash site, say). */
    private val retries = ArrayList<Job>()
    private val overlay by lazy { CrashAlertOverlay(context, this) }

    fun start() {
        createChannel()
        sms.start()
        scope.launch { session.crashes.collect { onCrash(it) } }
    }

    /** A crash was detected (or simulated from Profile in debug builds). */
    fun onCrash(report: CrashReport) {
        if (_state.value is CrashAlertState.Countdown) return
        scope.launch {
            val safety = settings.settings.first().safety
            val contacts = safety.contacts
            // No fix yet (just started, a tunnel): the phone's last known place, with its age.
            val place = if (report.latitude == null || report.longitude == null) LastKnownPlace.of(context) else null
            val withBattery = report.copy(
                batteryPercent = report.batteryPercent ?: battery.current()?.percent,
                latitude = place?.latitude ?: report.latitude,
                longitude = place?.longitude ?: report.longitude,
                accuracyM = place?.accuracyM ?: report.accuracyM,
                locationAgeMillis = place?.ageMillis ?: report.locationAgeMillis,
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
        nagging?.cancel()
        Log.i(TAG, "Crash alert cancelled by the rider (false alarm)")
        _state.value = CrashAlertState.Cancelled(s.report, s.contacts)
        stopAlarm()
    }

    fun sendNow() {
        val s = _state.value as? CrashAlertState.Countdown ?: return
        countdown?.cancel()
        nagging?.cancel()
        stopAlarm()
        val text = AlertMessage.crash(s.report)
        _state.value = CrashAlertState.Sent(s.report, s.contacts, s.contacts.associate { it.phone to SmsStatus.Sending }, text, System.currentTimeMillis())
        cancelRetries()
        s.contacts.forEach { c ->
            sendRetrying(c.phone, text) { status ->
                if (status is SmsStatus.Failed) Log.w(TAG, "An alert text wasn't sent: ${status.reason}")
                _state.update { st -> if (st is CrashAlertState.Sent) st.copy(delivered = st.delivered + (c.phone to status)) else st }
            }
        }
        followUp?.cancel()
        followUp = scope.launch { followUpLocation(s) }
    }

    /** "I'm OK" after an alert went out. */
    fun allClear() {
        val s = _state.value as? CrashAlertState.Sent ?: return
        followUp?.cancel()
        // An alert still waiting for signal no longer needs to go; tell those it reached.
        cancelRetries()
        val name = s.report.riderName
        val reached = s.contacts.filter { s.delivered[it.phone] == SmsStatus.Sent }
        reached.forEach { sendRetrying(it.phone, AlertMessage.allClear(name)) }
        _state.value = s.copy(allClearSent = true, retrying = false)
    }

    /**
     * Sends [text] to [phone], and if it fails (no signal, network busy) tries again every 30 s
     * for up to 15 minutes: a crash site often has no signal for a while. [onStatus] gets every
     * attempt's outcome.
     */
    private fun sendRetrying(phone: String, text: String, onStatus: (SmsStatus) -> Unit = {}) {
        val job = scope.launch {
            val until = System.currentTimeMillis() + RETRY_FOR_MILLIS
            while (true) {
                val status = sms.sendAndWait(phone, text)
                val again = status is SmsStatus.Failed && sms.canSend() && System.currentTimeMillis() + RETRY_EVERY_MILLIS < until
                onStatus(if (again) SmsStatus.Failed((status as SmsStatus.Failed).reason + " · trying again") else status)
                if (!again) break
                markRetrying()
                delay(RETRY_EVERY_MILLIS)
            }
        }
        synchronized(retries) { retries += job }
        job.invokeOnCompletion {
            synchronized(retries) { retries -= job }
            markRetrying()
        }
    }

    private fun markRetrying() {
        val any = synchronized(retries) { retries.any { it.isActive } }
        _state.update { st -> if (st is CrashAlertState.Sent && st.retrying != any) st.copy(retrying = any) else st }
    }

    private fun cancelRetries() {
        val jobs = synchronized(retries) { retries.toList().also { retries.clear() } }
        jobs.forEach { it.cancel() }
    }

    /** The screen was closed. */
    fun dismiss() {
        if (_state.value is CrashAlertState.Countdown) return
        _state.value = null
        overlay.hide()
    }

    /** The alert screen itself is showing: the window over other apps can go. */
    fun onScreenShown() = overlay.hide()

    /** Profile's "Send test message": each contact with what happened to their text. */
    suspend fun sendTest(): List<Pair<EmergencyContact, SmsStatus>> {
        val s = settings.settings.first().safety
        val frame = session.frame.value
        val report = CrashReport(
            timeMillis = System.currentTimeMillis(),
            latitude = frame?.latitude, longitude = frame?.longitude, accuracyM = frame?.gpsAccuracyM,
            speedBeforeMps = null, bikeName = session.active.value?.bikeName.orEmpty(), riderName = s.riderName,
            batteryPercent = battery.current()?.percent, medical = s.medical,
        )
        val text = AlertMessage.crash(report, test = true)
        return s.contacts.map { it to sms.sendAndWait(it.phone, text) }
    }

    fun canSendSms(): Boolean = sms.canSend()

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
                s.contacts.forEach { sendRetrying(it.phone, text) }
                return
            }
        }
    }

    /**
     * Shows the alert, as strongly as the phone allows:
     * 1. the full-screen alert (over the lock screen too) where the app may use one;
     * 2. otherwise, with "Display over other apps" on, the same screen as a window over any app,
     *    which also lets the app open the alert screen itself (Android 15+ needs a visible window);
     * 3. and always a loud notification with a live countdown and I'm OK / Send now, which pops up
     *    again every 10 s while it can't take over the screen.
     */
    private fun showScreen() {
        val intent = Intent(context, CrashAlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val fullScreen = canUseFullScreen()
        if (!fullScreen) overlay.show()
        // Straight to the screen when allowed (app open, or one of its windows showing).
        runCatching { context.startActivity(intent) }
        notifyAlert(intent, fullScreen, alertAgain = true)
        if (!fullScreen) {
            nagging?.cancel()
            nagging = scope.launch(Dispatchers.Main) {
                while (_state.value is CrashAlertState.Countdown) {
                    delay(NAG_MILLIS)
                    if (_state.value is CrashAlertState.Countdown) notifyAlert(intent, false, alertAgain = true)
                }
            }
        }
    }

    private fun notifyAlert(intent: Intent, fullScreen: Boolean, alertAgain: Boolean) {
        val s = _state.value as? CrashAlertState.Countdown ?: return
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, CrashAlertActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Possible crash: are you OK?")
            .setContentText(if (s.contacts.isEmpty()) "Tap I'm OK if you're fine." else "Your contacts get your location when the timer ends, unless you're OK.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // A live countdown to the texts going out.
            .setWhen(s.deadlineMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(0, "I'm OK", action(CrashAlertActionReceiver.ACTION_OK, 1))
            .addAction(0, "Send now", action(CrashAlertActionReceiver.ACTION_SEND, 2))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(!alertAgain)
            .setTimeoutAfter((s.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0) + 5_000)
            .apply { if (fullScreen) setFullScreenIntent(pending, true) }
            .build()
        runCatching { context.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, n) }
    }

    /** Whether the app may take over the screen (Android 14+ asks; Play only grants it to some apps). */
    private fun canUseFullScreen(): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService<NotificationManager>()?.canUseFullScreenIntent() == true

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
        nagging?.cancel()
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
        /** How often the notification pops up again while the alert can't take over the screen. */
        private const val NAG_MILLIS = 10_000L
        /** A text that didn't go is tried again this often, for this long. */
        private const val RETRY_EVERY_MILLIS = 30_000L
        private const val RETRY_FOR_MILLIS = 15 * 60_000L
    }
}
