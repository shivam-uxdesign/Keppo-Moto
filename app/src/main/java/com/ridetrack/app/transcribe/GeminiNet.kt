package com.ridetrack.app.transcribe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Gemini couldn't be reached: no internet, or something on the network blocks Google's server. */
class GeminiUnreachable(val timedOut: Boolean, cause: Throwable?) : IOException(cause?.message, cause)

/** Telling "Gemini said no" apart from "the phone couldn't reach Gemini", and waiting for the internet. */
object GeminiNet {
    /** True when [e] (or what caused it) is about reaching the server, not about Gemini's answer. */
    fun isUnreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
        it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is GeminiUnreachable ||
            (it is SocketException && it.message?.contains("abort", ignoreCase = true) == true)
    }

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
}
