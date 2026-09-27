package com.ridetrack.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bike photos live in app-private storage (the picker's URI grant is temporary), scaled
 * down so a card never decodes a 12 MP original.
 */
object BikePhotos {
    private const val MAX_EDGE = 1600

    private fun dir(context: Context) = File(context.filesDir, "bikes").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** Copies and downscales the picked image; returns the stored file name, or null on failure. */
    suspend fun import(context: Context, uri: Uri, bikeId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(context, uri) ?: return@runCatching null
            val name = "$bikeId-${System.currentTimeMillis()}.jpg"
            file(context, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            name
        }.getOrNull()
    }

    suspend fun delete(context: Context, name: String?) = withContext(Dispatchers.IO) {
        if (name != null) runCatching { file(context, name).delete() }
    }

    suspend fun load(context: Context, name: String, maxEdge: Int = MAX_EDGE): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(context, name)
        if (!f.exists()) return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
}
