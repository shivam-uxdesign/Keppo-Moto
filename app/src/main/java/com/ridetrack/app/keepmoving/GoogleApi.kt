package com.ridetrack.app.keepmoving

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One request to a Google Maps Platform web service with the app's key. The key is restricted to
 * this Android app, so every request says which app it is (package and signing SHA-1).
 */
object GoogleApi {
    /** The HTTP status and answer; status -1 when Google can't be reached. */
    fun call(context: Context, key: String, url: String, body: String?, fieldMask: String?): Pair<Int, String> = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = if (body != null) "POST" else "GET"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("X-Goog-Api-Key", key)
            fieldMask?.let { c.setRequestProperty("X-Goog-FieldMask", it) }
            c.setRequestProperty("X-Android-Package", context.packageName)
            certSha1(context)?.let { c.setRequestProperty("X-Android-Cert", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            c.disconnect()
        }
    }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }

    @Volatile private var sha1: String? = null

    /** The SHA-1 of the key this app is signed with, as Google's key restrictions want it. */
    fun certSha1(context: Context): String? = sha1 ?: runCatching {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()?.also { sha1 = it }
}
