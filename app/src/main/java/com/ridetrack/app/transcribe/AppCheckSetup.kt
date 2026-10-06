package com.ridetrack.app.transcribe

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.ridetrack.app.BuildConfig

/**
 * App Check: only the real Keppo Moto can use the project's Gemini allowance. Play Store builds
 * prove it with Play Integrity; test builds (installed by hand) with a debug token the rider
 * adds once in the Firebase console (Profile › Moments shows it).
 */
object AppCheckSetup {
    fun install(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        // A fixed token for test builds: the debug provider uses it instead of making a new one per install.
        if (BuildConfig.DEBUG && BuildConfig.APPCHECK_DEBUG_TOKEN.isNotBlank()) {
            context.getSharedPreferences(store(), Context.MODE_PRIVATE).edit().putString(SECRET, BuildConfig.APPCHECK_DEBUG_TOKEN).apply()
        }
        Firebase.appCheck.installAppCheckProviderFactory(
            if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance() else PlayIntegrityAppCheckProviderFactory.getInstance(),
        )
        // Makes the debug provider create (and store) its token now, so it can be shown.
        if (BuildConfig.DEBUG) runCatching { Firebase.appCheck.getAppCheckToken(false) }
    }

    /** This install's debug token, to add in Firebase › App Check › Manage debug tokens; null on Play builds. */
    fun debugToken(context: Context): String? {
        if (!BuildConfig.DEBUG || FirebaseApp.getApps(context).isEmpty()) return null
        return context.getSharedPreferences(store(), Context.MODE_PRIVATE).getString(SECRET, null)
    }

    /** Firebase's App Check refused the request: this install's debug token isn't registered (or isn't valid). */
    fun isRejected(e: Throwable): Boolean = "${e.message} ${e.cause?.message}".contains("App Check", ignoreCase = true)

    /** Where the debug provider keeps its token (Firebase's own names). */
    private fun store() = "com.google.firebase.appcheck.debug.store.${FirebaseApp.getInstance().persistenceKey}"
    private const val SECRET = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
}
