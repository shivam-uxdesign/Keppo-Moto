package com.ridetrack.app.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** What happened to one text, as the phone's radio reports it. */
sealed interface SmsStatus {
    data object Sending : SmsStatus
    data object Sent : SmsStatus
    data class Failed(val reason: String) : SmsStatus
}

/**
 * Sends texts and reports whether each one really left the phone. On dual-SIM phones it
 * uses the SMS SIM, or the first active SIM when SMS is set to "ask every time" (the
 * plain default manager can't send then, and fails without a word).
 */
class SmsSender(private val context: Context) {
    private class Pending(var partsLeft: Int, var failure: String?, val done: (SmsStatus) -> Unit)

    private val pending = HashMap<Int, Pending>()
    private val ids = AtomicInteger(1)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val code = resultCode
            val p = synchronized(pending) { pending[id] } ?: return
            val finished = synchronized(pending) {
                if (code != Activity.RESULT_OK && p.failure == null) p.failure = reason(code)
                p.partsLeft--
                if (p.partsLeft <= 0) pending.remove(id) != null else false
            }
            if (finished) p.done(p.failure?.let { SmsStatus.Failed(it) } ?: SmsStatus.Sent)
        }
    }

    fun start() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun canSend(): Boolean = granted(Manifest.permission.SEND_SMS)

    /** Sends [text] to [phone]; [onResult] gets the outcome once every part is out (or failed). */
    fun send(phone: String, text: String, onResult: (SmsStatus) -> Unit = {}) {
        if (!canSend()) return onResult(SmsStatus.Failed("SMS permission is off"))
        val manager = manager() ?: return onResult(SmsStatus.Failed("No SIM for SMS"))
        try {
            val parts = manager.divideMessage(text)
            val id = ids.getAndIncrement()
            synchronized(pending) { pending[id] = Pending(parts.size, null, onResult) }
            val sent = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { i ->
                val intent = Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_ID, id)
                sent += PendingIntent.getBroadcast(context, id * 16 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            manager.sendMultipartTextMessage(phone, null, parts, sent, null)
        } catch (e: Exception) {
            Log.e(TAG, "SMS to $phone failed", e)
            onResult(SmsStatus.Failed(e.message ?: "Couldn't send"))
        }
    }

    /** [send], waiting (up to [timeoutMillis]) for the result. */
    suspend fun sendAndWait(phone: String, text: String, timeoutMillis: Long = 30_000L): SmsStatus {
        val result = CompletableDeferred<SmsStatus>()
        send(phone, text) { result.complete(it) }
        return withTimeoutOrNull(timeoutMillis) { result.await() } ?: SmsStatus.Failed("No answer from the network")
    }

    @SuppressLint("MissingPermission")
    private fun manager(): SmsManager? {
        var sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID && granted(Manifest.permission.READ_PHONE_STATE)) {
            // SMS set to "ask every time": use the first SIM that's in.
            sub = runCatching { context.getSystemService<SubscriptionManager>()?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId }
                .getOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService<SmsManager>() else @Suppress("DEPRECATION") SmsManager.getDefault()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base?.createForSubscriptionId(sub) else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun reason(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile signal"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile network off (airplane mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "The network refused it"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Too many texts at once"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Can't text that number"
        else -> "Couldn't send (code $code)"
    }

    private companion object {
        const val TAG = "SmsSender"
        const val ACTION_SENT = "com.ridetrack.app.SMS_SENT"
        const val EXTRA_ID = "id"
    }
}
