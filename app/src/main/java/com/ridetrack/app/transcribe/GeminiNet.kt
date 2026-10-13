package com.ridetrack.app.transcribe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicInteger

/** Gemini couldn't be reached: no internet, or something on the network blocks Google's server. */
class GeminiUnreachable(val timedOut: Boolean, cause: Throwable?) : IOException(cause?.message, cause)

/** Telling "Gemini said no" apart from "the phone couldn't reach Gemini", and waiting for the internet. */
object GeminiNet {
    /** The app, for checking the connection; set when the app starts (null in unit tests). */
    @Volatile var appContext: Context? = null

    /** How many times a request was cut off by the connection and sent again (for the log). */
    val drops = AtomicInteger()

    /** True when [e] (or what caused it) is about reaching the server, not about Gemini's answer. */
    fun isUnreachable(e: Throwable): Boolean = chain(e).any {
        it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is GeminiUnreachable || it is SocketException
    }

    /**
     * A request that was sent over the network and failed on the way (lost signal, a tower or 4G/5G
     * switch, a stalled or reset connection, an address not found): the same request may work moments
     * later. Not Gemini's answer (a refusal, a spent quota) and not a missing file.
     */
    fun isDropped(e: Throwable): Boolean = chain(e).any {
        it is GeminiUnreachable || it is UnresolvedAddressException ||
            (it is IOException && it !is FirebaseTranscriber.Busy && it !is FileNotFoundException) ||
            it.javaClass.simpleName.contains("Timeout")
    }

    /** [e], what caused it and what it suppressed (a retry's earlier failure). */
    private fun chain(e: Throwable): Sequence<Throwable> =
        generateSequence(e) { it.cause }.take(8).flatMap { sequenceOf(it) + it.suppressed.asSequence() }

    /** What to tell the rider. */
    fun message(e: Throwable): String {
        val timedOut = generateSequence(e) { it.cause }.take(8).any { (it as? GeminiUnreachable)?.timedOut == true || it is SocketTimeoutException }
        return if (timedOut) "Gemini took too long to answer (a slow connection). Studio tries again by itself."
        else "Can't reach Gemini: no internet, or something on this network blocks it (Private DNS, an ad-blocker or the Wi-Fi's filter). Studio tries again when the connection is back."
    }

    /** The phone has a working internet connection right now. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Suspends until the phone has a working internet connection (a network Android checked). */
    suspend fun awaitOnline(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        callbackFlow {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) trySend(Unit)
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            if (online(context)) trySend(Unit)
            awaitClose { cm.unregisterNetworkCallback(cb) }
        }.first()
    }

    /** Waits up to [maxMs] for a working connection; true once there is one (or when it can't be checked). */
    suspend fun awaitOnline(maxMs: Long): Boolean {
        val ctx = appContext ?: return true
        return withTimeoutOrNull(maxMs) { awaitOnline(ctx); true } == true
    }

    /** On mobile data (or a hotspot): send less per request, it uploads slowly and drops more often. */
    fun metered(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
}
