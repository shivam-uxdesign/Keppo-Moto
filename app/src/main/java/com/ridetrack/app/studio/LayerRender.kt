package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt

/** A layer's frame size: full output width, its own aspect. */
internal fun layerSize(l: LayerItem, outW: Int): Pair<Int, Int> {
    val h = (outW / l.aspect).roundToInt()
    // Encoders like even sizes.
    return outW to (h + (h and 1))
}

/**
 * Cuts a layer's corners (rounded or a circle) and draws its white frame, on the GPU, so the
 * main video shows around it.
 */
@UnstableApi
internal class MaskEffect(private val shape: LayerShape, private val border: Boolean) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = MaskProgram(shape, border)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = shape == LayerShape.RECT && !border
}

@UnstableApi
private class MaskProgram(private val shape: LayerShape, private val border: Boolean) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var w = 0
    private var h = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth
        h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uSize", floatArrayOf(w.toFloat(), h.toFloat()))
            val radius = when (shape) {
                LayerShape.RECT -> 0f
                LayerShape.ROUNDED -> minOf(w, h) * 0.08f
                LayerShape.CIRCLE -> minOf(w, h) / 2f
            }
            program.setFloatUniform("uRadius", radius)
            program.setFloatUniform("uBorder", if (border) maxOf(3f, w * 0.012f) else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
        // A rounded rectangle's distance field: outside is clear, the edge is antialiased, the frame is white.
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform float uRadius;
uniform float uBorder;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec2 p = vTexSamplingCoord * uSize;
  vec2 halfSize = uSize * 0.5;
  vec2 q = abs(p - halfSize) - (halfSize - vec2(uRadius));
  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
  float inside = clamp(0.5 - d, 0.0, 1.0);
  if (uBorder > 0.0) {
    float frame = clamp(d + uBorder + 0.5, 0.0, 1.0);
    c = mix(c, vec4(1.0, 1.0, 1.0, 1.0), frame);
  }
  gl_FragColor = vec4(c.rgb, c.a * inside);
}
"""
    }
}

/**
 * Where each sequence's frames go in the finished video. Sequence [i] < layers.size is layer
 * [layers][i] (the first is on top); the last is the main video, full frame.
 */
@UnstableApi
internal class LayerCompositor(private val layers: List<LayerItem>, private val outW: Int, private val outH: Int) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(outW, outH)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val l = layers.getOrNull(inputId) ?: return OverlaySettings.Builder().build()
        val t = presentationTimeUs / 1000
        if (t < l.startMs || t >= l.endMs) return OverlaySettings.Builder().setAlphaScale(0f).build()
        val k = TrackEdits.keyAt(l, t - l.startMs)
        // A short fade at its ends, so it doesn't pop.
        val edge = minOf(t - l.startMs, l.endMs - t).toFloat()
        val fade = (edge / 150f).coerceIn(0f, 1f)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(k.cx * 2f - 1f, 1f - k.cy * 2f)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(k.w, k.w)
            .setRotationDegrees(l.rotation)
            .setAlphaScale(l.opacity * fade)
            .build()
    }
}

/**
 * The slower way, for phones that can't combine videos: each layer's frames are read from its
 * clip (a few per second) and drawn on top of the main video with the other graphics.
 */
internal class LayerDrawer(private val context: Context, private val layers: List<LayerItem>, private val uriOf: (LayerItem) -> Uri?) {
    private val readers = HashMap<String, MediaMetadataRetriever>()
    private val cache = HashMap<String, Pair<Long, Bitmap>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE }

    /** Draws the layers showing at [reelMs] onto [c] (the full frame). */
    fun draw(c: Canvas, reelMs: Long) {
        for (l in layers.asReversed()) {
            if (reelMs < l.startMs || reelMs >= l.endMs) continue
            val local = reelMs - l.startMs
            val bmp = frameAt(l, l.inMs + local) ?: continue
            val k = TrackEdits.keyAt(l, local)
            val w = k.w * c.width
            val h = w / l.aspect
            val rect = RectF(k.cx * c.width - w / 2, k.cy * c.height - h / 2, k.cx * c.width + w / 2, k.cy * c.height + h / 2)
            val radius = when (l.shape) {
                LayerShape.RECT -> 0f
                LayerShape.ROUNDED -> minOf(w, h) * 0.08f
                LayerShape.CIRCLE -> minOf(w, h) / 2f
            }
            c.save()
            c.rotate(l.rotation, rect.centerX(), rect.centerY())
            val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
            c.clipPath(clip)
            paint.alpha = (255 * l.opacity * (minOf(local, l.endMs - reelMs) / 150f).coerceIn(0f, 1f)).roundToInt()
            // Centre-crop the frame into the layer's box.
            val scale = maxOf(rect.width() / bmp.width, rect.height() / bmp.height)
            val sw = rect.width() / scale
            val sh = rect.height() / scale
            val src = android.graphics.Rect(((bmp.width - sw) / 2).roundToInt(), ((bmp.height - sh) / 2).roundToInt(), ((bmp.width + sw) / 2).roundToInt(), ((bmp.height + sh) / 2).roundToInt())
            c.drawBitmap(bmp, src, rect, paint)
            c.restore()
            if (l.border) {
                frame.strokeWidth = maxOf(3f, w * 0.012f)
                c.save()
                c.rotate(l.rotation, rect.centerX(), rect.centerY())
                c.drawRoundRect(rect, radius, radius, frame)
                c.restore()
            }
        }
    }

    /** The frame nearest [ms] in the layer's clip, a tenth of a second at a time. */
    private fun frameAt(l: LayerItem, ms: Long): Bitmap? {
        val q = ms / 100 * 100
        cache[l.id]?.let { (t, b) -> if (t == q) return b }
        val r = readers.getOrPut(l.id) {
            MediaMetadataRetriever().apply { uriOf(l)?.let { u -> runCatching { setDataSource(context, u) } } }
        }
        val b = runCatching { r.getScaledFrameAtTime(q * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 720, 1280) }.getOrNull() ?: return cache[l.id]?.second
        cache.put(l.id, q to b)?.second?.recycle()
        return b
    }

    fun release() {
        readers.values.forEach { runCatching { it.release() } }
        cache.values.forEach { it.second.recycle() }
        readers.clear()
        cache.clear()
    }
}

/** Plain files the renderer lines tracks up with: a clear picture and silence. */
internal object Fillers {
    /** A small black picture for the time a layer isn't showing (it's fully clear then). */
    fun picture(context: Context): File {
        val f = File(context.cacheDir, "studio-filler.png")
        if (!f.isFile) {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.BLACK)
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        return f
    }

    /** [ms] of silence as a WAV (44.1 kHz mono), so a sound can start later in the Reel. */
    fun silence(context: Context, ms: Long): File {
        val dir = File(context.cacheDir, "studio-silence").apply { mkdirs() }
        val f = File(dir, "silence-$ms.wav")
        if (f.isFile) return f
        val rate = 44_100
        val samples = (ms * rate / 1000).toInt().coerceAtLeast(1)
        val data = samples * 2
        RandomAccessFile(f, "rw").use { raf ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(data)
            raf.write(h.array())
            raf.setLength(44L + data)
        }
        return f
    }
}

/**
 * Takes the low rumble out from under a voice (wind, engine drone): a gentle high-pass at about
 * 110 Hz.
 */
@UnstableApi
internal class HighPassProcessor(private val cutoffHz: Double = 110.0) : BaseAudioProcessor() {
    private var a = 0.0
    private var prevIn = DoubleArray(8)
    private var prevOut = DoubleArray(8)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        val rc = 1.0 / (2 * PI * cutoffHz)
        val dt = 1.0 / inputAudioFormat.sampleRate
        a = rc / (rc + dt)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = inputAudioFormat.channelCount
        if (prevIn.size < ch) { prevIn = DoubleArray(ch); prevOut = DoubleArray(ch) }
        val out = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        var c = 0
        while (inputBuffer.remaining() >= 2) {
            val x = inputBuffer.getShort().toDouble()
            val y = a * (prevOut[c] + x - prevIn[c])
            prevIn[c] = x
            prevOut[c] = y
            out.putShort(y.roundToInt().coerceIn(-32768, 32767).toShort())
            c = (c + 1) % ch
        }
        out.flip()
    }

    override fun onReset() {
        prevIn.fill(0.0)
        prevOut.fill(0.0)
    }
}
