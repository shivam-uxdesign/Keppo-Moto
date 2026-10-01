package com.ridetrack.app.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Drive access was granted once but now needs the rider to approve it again. */
class ReconnectNeeded : Exception("Google Drive needs to be reconnected")

/**
 * Drive access through Google Play services: no Keppo account, no password, just the
 * rider's Google account and the `drive.file` scope (only files Keppo apps create).
 * Play services matches this app by package name + signing key to the "Keppo" Cloud project.
 */
class DriveAuth(context: Context) {
    private val client = Identity.getAuthorizationClient(context)
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE), Scope(EMAIL)))
        .build()

    sealed interface Outcome {
        data class Granted(val token: String, val email: String?) : Outcome
        /** First connect (or access expired): show Google's consent screen. */
        data class NeedsConsent(val intent: PendingIntent) : Outcome
    }

    suspend fun authorize(): Outcome = toOutcome(client.authorize(request).await())

    /** Reads the consent screen's answer. */
    fun fromConsent(data: Intent?): Outcome? =
        runCatching { toOutcome(client.getAuthorizationResultFromIntent(data)) }.getOrNull()

    /** A fresh access token for background work; throws [ReconnectNeeded] if consent is needed again. */
    suspend fun token(): String = when (val o = authorize()) {
        is Outcome.Granted -> o.token
        is Outcome.NeedsConsent -> throw ReconnectNeeded()
    }

    private fun toOutcome(r: AuthorizationResult): Outcome {
        val pending = r.pendingIntent
        val token = r.accessToken
        return if (r.hasResolution() && pending != null) Outcome.NeedsConsent(pending)
        else if (token != null) Outcome.Granted(token, runCatching { r.toGoogleSignInAccount()?.email }.getOrNull())
        else throw ReconnectNeeded()
    }

    private companion object {
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val EMAIL = "email"
    }
}
