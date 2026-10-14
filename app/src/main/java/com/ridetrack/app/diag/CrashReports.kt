package com.ridetrack.app.diag

import android.content.Context
import android.telephony.TelephonyManager
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Crash reports (Firebase Crashlytics): what the app was doing when it crashed, never rides,
 * places, videos or messages. Off until it's settled: in the EU, EEA, UK and Switzerland the
 * rider is asked first (a card on Home); elsewhere it's on. Either way it can be turned off in
 * About & privacy. Collection starts off in the manifest, so nothing goes before this decides.
 */
class CrashReports(private val context: Context) {
    private val prefs = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
    private val _choice = MutableStateFlow(stored())

    /** The rider's choice; null when not asked yet (and asking is needed). */
    val choice: StateFlow<Boolean?> = _choice.asStateFlow()

    /** Asking is needed: the rider is in a region where reports need consent and hasn't chosen. */
    val needsAsking: Boolean get() = _choice.value == null && Regions.needsConsent(country())

    fun start() = apply()

    fun set(on: Boolean) {
        prefs.edit().putBoolean(CHOICE, on).apply()
        _choice.value = on
        apply()
    }

    /** On: the rider said yes, or didn't need to be asked. */
    val on: Boolean get() = _choice.value ?: !Regions.needsConsent(country())

    private fun apply() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        runCatching { FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = on }
    }

    /** An error the app caught and logged, sent along when reports are on. */
    fun record(e: Throwable) {
        if (!on || FirebaseApp.getApps(context).isEmpty()) return
        runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
    }

    private fun stored(): Boolean? = if (prefs.contains(CHOICE)) prefs.getBoolean(CHOICE, false) else null

    /** The rider's country: the SIM's, else the network's, else the phone's language region. */
    private fun country(): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT).orEmpty()
    }

    private companion object {
        const val CHOICE = "choice"
    }
}

/** Where crash reports need the rider's consent first. Pure, unit-tested. */
object Regions {
    /** The EU, the rest of the EEA, the UK and Switzerland. */
    private val CONSENT = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU",
        "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO", "GB", "CH",
    )

    /** Unknown country: ask, to be safe. */
    fun needsConsent(country: String): Boolean = country.isBlank() || country.uppercase(Locale.ROOT) in CONSENT
}
