package com.ridetrack.app.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes share graphics as PNG (keeps the overlay's transparency) and hands them out. */
object ShareImages {
    private const val AUTHORITY_SUFFIX = ".exports"

    /** PNG keeps transparency; [jpeg] for photos with a picture behind (much smaller). */
    suspend fun toCacheUri(context: Context, bitmap: Bitmap, name: String, jpeg: Boolean = false): Uri = withContext(Dispatchers.IO) {
        val dir = sharesDir(context)
        val file = File(dir, if (jpeg) "$name.jpg" else "$name.png")
        file.outputStream().use {
            if (jpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        uriFor(context, file)
    }

    /** A fresh cache folder for share outputs (only the latest ones are kept). */
    fun sharesDir(context: Context): File = File(context.cacheDir, "shares").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    fun share(context: Context, uri: Uri, mime: String = "image/png") {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        runCatching { context.startActivity(Intent.createChooser(send, "Share ride")) }
    }

    /** Shares several files at once (e.g. Reels to WhatsApp or Instagram). */
    fun shareMany(context: Context, uris: List<Uri>, mime: String) {
        if (uris.size == 1) return share(context, uris[0], mime)
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(mime)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
    }

    /** Copies the image so it can be pasted as a sticker (e.g. into an Instagram story). */
    fun copy(context: Context, uri: Uri): Boolean {
        val cm = context.getSystemService<ClipboardManager>() ?: return false
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Keppo Moto", uri))
        return true
    }

    /** Copies a video into Movies/Keppo Moto (Android 10+, no permission needed). */
    suspend fun saveVideo(context: Context, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Keppo Moto")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { resolver.delete(uri, null, null) }.isSuccess
    }

    /** Saves to Pictures/Keppo Moto. API 29+ needs no permission; older phones fall back to sharing. */
    suspend fun saveToPhotos(context: Context, bitmap: Bitmap, name: String, jpeg: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, if (jpeg) "$name.jpg" else "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Keppo Moto")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(uri)?.use {
                if (jpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { resolver.delete(uri, null, null) }.isSuccess
    }
}
