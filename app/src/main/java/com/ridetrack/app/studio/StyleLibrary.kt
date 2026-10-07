package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * The rider's styles (built-in ones plus their own), favourites, how often each was used, and
 * the brand kit. Kept in files/studio-styles.json and files/brand/ (both go in the Drive backup).
 */
class StyleLibrary(private val context: Context) {
    private val file = File(context.filesDir, FILE)
    private val brandDir = File(context.filesDir, "brand").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("studio_styles", Context.MODE_PRIVATE)
    private val _styles = MutableStateFlow(load())
    /** Built-in first, then the rider's; favourites marked. */
    val styles: StateFlow<List<StudioStyle>> = _styles.asStateFlow()
    private val _brand = MutableStateFlow(readBrand())
    val brand: StateFlow<BrandKit> = _brand.asStateFlow()

    fun newId(): String = "st-" + UUID.randomUUID().toString().take(8)

    fun get(id: String): StudioStyle? = _styles.value.firstOrNull { it.id == id }

    /** Adds or replaces [s] (a built-in one is saved as the rider's change to it). */
    fun save(s: StudioStyle) {
        _styles.value = if (_styles.value.any { it.id == s.id }) _styles.value.map { if (it.id == s.id) s else it } else _styles.value + s
        write()
    }

    fun delete(id: String) {
        _styles.value = _styles.value.filter { it.id != id || it.builtIn }
        write()
    }

    fun favourite(id: String) = get(id)?.let { save(it.copy(favourite = !it.favourite)) }

    /** A built-in style as it was first made (for Reset). */
    fun original(s: StudioStyle): StudioStyle = Styles.builtIns.firstOrNull { it.id == s.id } ?: s

    /** A style was used on a piece: counted, so suggestions favour what the rider uses. */
    fun used(id: String) = prefs.edit().putInt("used_$id", uses(id) + 1).apply()

    fun uses(id: String): Int = prefs.getInt("used_$id", 0)

    /** For Gemini choosing a style per piece: favourites and the most used first. */
    fun forSuggestions(): List<StudioStyle> = _styles.value.sortedWith(compareByDescending<StudioStyle> { it.favourite }.thenByDescending { uses(it.id) }).take(8)

    // ---- brand kit ----------------------------------------------------------------------------

    fun setBrand(b: BrandKit) {
        _brand.value = b
        prefs.edit().putString("brand_handle", b.handle).putString("brand_color", b.color?.toString()).apply()
    }

    /** Copies an uploaded logo (any picture) into the brand kit, as a PNG. */
    suspend fun setLogo(uri: Uri?): Boolean = withContext(Dispatchers.IO) {
        val out = File(brandDir, "logo.png")
        if (uri == null) { out.delete(); _brand.value = _brand.value.copy(logo = null); return@withContext true }
        val ok = runCatching {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching false
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            true
        }.getOrDefault(false)
        if (ok) _brand.value = _brand.value.copy(logo = out.path)
        ok
    }

    /** Copies an uploaded font (TTF or OTF) into the brand kit; false if it isn't a font Android can use. */
    suspend fun setFont(uri: Uri?): Boolean = withContext(Dispatchers.IO) {
        val out = File(brandDir, "font.ttf")
        if (uri == null) { out.delete(); _brand.value = _brand.value.copy(font = null); return@withContext true }
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            Typeface.createFromFile(out) != Typeface.DEFAULT
        }.getOrDefault(false)
        if (ok) _brand.value = _brand.value.copy(font = out.path) else out.delete()
        ok
    }

    private fun readBrand(): BrandKit = BrandKit(
        handle = prefs.getString("brand_handle", "").orEmpty(),
        logo = File(brandDir, "logo.png").takeIf { it.isFile }?.path,
        font = File(brandDir, "font.ttf").takeIf { it.isFile }?.path,
        color = prefs.getString("brand_color", null)?.toIntOrNull(),
    )

    // ---- previews and reference videos -------------------------------------------------------

    /**
     * What [s] looks like on the rider's own [frame] (their best clip): its colour, captions, a
     * text and the speed, drawn on a still. Small (540×960).
     */
    suspend fun preview(s: StudioStyle, frame: Bitmap?): Bitmap = withContext(Dispatchers.Default) {
        val w = 540
        val h = 960
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (frame != null) {
            val scale = maxOf(w.toFloat() / frame.width, h.toFloat() / frame.height)
            val m = android.graphics.Matrix().apply { setScale(scale, scale); postTranslate((w - frame.width * scale) / 2, (h - frame.height * scale) / 2) }
            c.drawBitmap(frame, m, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(grade(s)) })
        } else {
            c.drawColor(android.graphics.Color.rgb(40, 44, 52))
        }
        val art = StudioArt(context, w, h)
        val f = FrameAt(vibe = s.base, localMs = 1_400, durMs = 4_000, hasPrev = false, hasNext = false, nextLabel = "", nextTime = "")
        art.drawClip(c, f, listOf(CaptionLine(0, 3_000, "Sixty five on the flyover")), if (s.speedBadge) 62 else -1, "6:21 pm", true, null, s.captions)
        val t = TextItem("p", 0, 3_000, "Monsoon run", y = 0.22f, look = s.textLook, color = s.textColor, animIn = TextAnim.NONE, animOut = TextAnim.NONE)
        art.drawTextItem(c, s.base, t, 1_000, 3_000)
        if (s.speedSticker) art.drawSticker(c, StickerItem("sp", StickerKind.SPEED, 0, 3_000, 0.22f, 0.86f, 0.8f), 1_000, 62, 18)
        if (s.leanSticker) art.drawSticker(c, StickerItem("ln", StickerKind.LEAN, 0, 3_000, 0.78f, 0.86f, 0.8f), 1_000, 62, 18)
        out
    }

    /** The style's colour as a picture filter (close to the made video's grade). */
    private fun grade(s: StudioStyle): ColorMatrix {
        val m = ColorMatrix()
        val base = when (s.base) { Vibe.HYPE -> 0.16f; Vibe.CINE -> -0.2f; Vibe.CHILL -> -0.12f; Vibe.VLOG -> 0.08f }
        m.setSaturation(1f + (if (s.color.look) base else 0f) + s.color.saturation * 0.4f)
        val warm = s.color.warmth * 0.12f + if (s.color.look && s.base == Vibe.CHILL) 0.06f else 0f
        val bright = s.color.exposure * 60f
        m.postConcat(ColorMatrix(floatArrayOf(1f + warm, 0f, 0f, 0f, bright, 0f, 1f, 0f, 0f, bright, 0f, 0f, 1f - warm, 0f, bright, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    /** About [n] frames across a reference video, as JPEGs for Gemini (small, to keep the request light). */
    suspend fun referenceFrames(uri: Uri, n: Int = 8): List<ByteArray> = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext emptyList()
            (0 until n).mapNotNull { i ->
                val t = dur * (2 * i + 1) / (2 * n)
                r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)?.let { b ->
                    ByteArrayOutputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, 75, o); b.recycle(); o.toByteArray() }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun load(): List<StudioStyle> {
        val mine = runCatching { StyleJson.readAll(file.readText()) }.getOrDefault(emptyList())
        // A built-in the rider changed is saved under its own id: theirs replaces the original.
        val builtIns = Styles.builtIns.map { b -> mine.firstOrNull { it.id == b.id }?.copy(builtIn = true) ?: b }
        return builtIns + mine.filter { m -> Styles.builtIns.none { it.id == m.id } }
    }

    private fun write() {
        val changed = _styles.value.filter { s -> !s.builtIn || Styles.builtIns.firstOrNull { it.id == s.id } != s }
        runCatching { file.writeText(StyleJson.writeAll(changed)) }
    }

    companion object {
        const val FILE = "studio-styles.json"
    }
}
