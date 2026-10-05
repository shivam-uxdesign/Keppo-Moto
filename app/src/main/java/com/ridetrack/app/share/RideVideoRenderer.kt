package com.ridetrack.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.ridetrack.app.data.MapStyle
import com.ridetrack.app.moments.ClipWriter
import com.ridetrack.app.ui.common.positionAtSmooth
import com.ridetrack.app.ui.components.styleBuilder
import com.ridetrack.app.ui.detail.TrackData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Renders a [RideVideoPlan] to a 1080×1920 H.264 MP4: the ride on the dark 3D map, followed
 * from behind the bike, with the share overlay drawn on every frame. At a moment the map
 * pauses (dimmed) and the clip plays in a large card in the centre, with its sound.
 *
 * Map frames come from a [MapSnapshotter] re-aimed for every frame (tiles come from the
 * network the first time, then the cache), so rendering is slower than real time.
 */
class RideVideoRenderer(private val context: Context) {

    data class Pin(val latitude: Double, val longitude: Double, val color: Int)

    suspend fun render(
        data: TrackData,
        plan: RideVideoPlan,
        /** Each moment's file, by id: the clip (.mp4) or the photo (.jpg). */
        files: Map<String, File>,
        pins: List<Pin>,
        /** Draws the overlay for ride time (ms) onto a frame-sized canvas. */
        drawOverlay: (Canvas, Int, Int, Long) -> Unit,
        output: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = runCatching {
        output.delete()
        val frames = (plan.totalMillis * FPS / 1000).toInt().coerceAtLeast(1)
        val encoder = Encoder(output, audioFormatOf(plan, files))
        var snapshotter: MapSnapshotter? = null
        var retriever: MediaMetadataRetriever? = null
        var retrieverFor: String? = null
        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        var lastMap: Bitmap? = null
        var heading = Float.NaN
        var audioDoneFor: VideoPart.Hold? = null
        try {
            snapshotter = withContext(Dispatchers.Main) { newSnapshotter(data) }
            for (n in 0 until frames) {
                coroutineContext.ensureActive()
                val outMs = n * 1000L / FPS
                val (part, local) = plan.at(outMs) ?: break
                val rideTime = plan.rideTimeAt(outMs) ?: data.startMillis
                when (part) {
                    is VideoPart.Map -> {
                        val pos = data.samples.positionAtSmooth(rideTime.toDouble())
                        val target = data.bearingAtTime(rideTime.toDouble())
                        if (target != null) heading = if (heading.isNaN()) target else easeAngle(heading, target, 0.12f)
                        val shot = pos?.let { snapshot(snapshotter, camera(it.latitude, it.longitude, heading)) }
                        canvas.drawColor(BACKGROUND)
                        if (shot != null) {
                            canvas.drawBitmap(shot.bitmap, null, RectF(0f, 0f, W.toFloat(), H.toFloat()), null)
                            drawPins(canvas, shot, pins, shot.bitmap.width)
                            val bike = shot.pixelForLatLng(LatLng(pos.latitude, pos.longitude))
                            val k = W.toFloat() / shot.bitmap.width
                            drawBike(canvas, bike.x * k, bike.y * k)
                            lastMap?.recycle()
                            lastMap = frame.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    }
                    is VideoPart.Hold -> {
                        canvas.drawColor(BACKGROUND)
                        lastMap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                        canvas.drawColor(Color.argb(150, 0, 0, 0))
                        val m = part.moment
                        val file = files[m.id]
                        val picture = when {
                            file == null -> null
                            m.isClip -> {
                                if (retrieverFor != m.id) {
                                    retriever?.release()
                                    retriever = MediaMetadataRetriever().apply { setDataSource(file.path) }
                                    retrieverFor = m.id
                                }
                                clipFrame(retriever!!, part.clipFrom + local)
                            }
                            else -> ClipWriter.load(file, 1280)
                        }
                        drawCard(canvas, picture, m.label)
                        picture?.recycle()
                        if (m.isClip && audioDoneFor !== part && file != null) {
                            audioDoneFor = part
                            encoder.copyAudio(file, part.clipFrom, part.clipTo, startOutMs = outMs - local)
                        }
                    }
                }
                drawOverlay(canvas, W, H, rideTime)
                encoder.addFrame(frame)
                onProgress((n + 1) * 100 / frames)
            }
            encoder.finish()
            output
        } catch (e: Throwable) {
            encoder.abort()
            output.delete()
            throw e
        } finally {
            retriever?.release()
            lastMap?.recycle()
            frame.recycle()
            snapshotter?.let { s -> withContext(Dispatchers.Main) { runCatching { s.cancel() } } }
        }
    }

    // ---- Map ------------------------------------------------------------------------------

    private fun newSnapshotter(data: TrackData): MapSnapshotter {
        val samples = data.samples.filter { it.latitude != null && it.longitude != null }
        val step = (samples.size / MAX_SEGMENTS + 1).coerceAtLeast(1)
        val pts = samples.filterIndexed { i, _ -> i % step == 0 || i == samples.lastIndex }
        val top = pts.mapNotNull { it.speedMps }.maxOrNull()?.takeIf { it > 1.0 } ?: 1.0
        val features = (1 until pts.size).map { i ->
            val a = pts[i - 1]
            val b = pts[i]
            Feature.fromGeometry(
                LineString.fromLngLats(listOf(Point.fromLngLat(a.longitude!!, a.latitude!!), Point.fromLngLat(b.longitude!!, b.latitude!!))),
            ).also { it.addStringProperty("color", hex(heat(((b.speedMps ?: 0.0) / top).toFloat()))) }
        }
        val style = styleBuilder(MapStyle.DARK)
            .withSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(features)))
            .withLayers(
                LineLayer("video-route-casing", ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#000000"),
                    PropertyFactory.lineOpacity(0.6f),
                    PropertyFactory.lineWidth(9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
                LineLayer("video-route", ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        val first = samples.firstOrNull()
        val options = MapSnapshotter.Options(W / RATIO, H / RATIO)
            .withPixelRatio(RATIO.toFloat())
            .withLogo(false)
            .withStyleBuilder(style)
            .withCameraPosition(camera(first?.latitude ?: 0.0, first?.longitude ?: 0.0, 0f))
        return MapSnapshotter(context, options)
    }

    /** Behind and above the bike, looking along its heading; the bike sits low in the frame. */
    private fun camera(lat: Double, lon: Double, heading: Float) = CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .zoom(FOLLOW_ZOOM)
        .tilt(FOLLOW_TILT)
        .bearing(if (heading.isNaN()) 0.0 else heading.toDouble())
        .padding(0.0, H / RATIO * FOLLOW_TOP_PAD, 0.0, 0.0)
        .build()

    private suspend fun snapshot(s: MapSnapshotter?, cam: CameraPosition): MapSnapshot? {
        s ?: return null
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    s.setCameraPosition(cam)
                    s.start(
                        { shot -> if (cont.isActive) cont.resume(shot) },
                        { err ->
                            Log.w(TAG, "Map frame failed: $err")
                            if (cont.isActive) cont.resume(null)
                        },
                    )
                }
            }
        }
    }

    private fun drawPins(c: Canvas, shot: MapSnapshot, pins: List<Pin>, shotWidth: Int) {
        val k = W.toFloat() / shotWidth
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (pin in pins) {
            val pt = shot.pixelForLatLng(LatLng(pin.latitude, pin.longitude))
            val x = pt.x * k
            val y = pt.y * k
            if (x < -40 || x > W + 40 || y < H * 0.18f || y > H + 40) continue
            p.color = pin.color
            p.style = Paint.Style.FILL
            c.drawCircle(x, y - 34f, 16f, p)
            p.strokeWidth = 5f
            c.drawLine(x, y - 18f, x, y, p)
            p.style = Paint.Style.STROKE
            p.color = Color.BLACK
            p.strokeWidth = 4f
            c.drawCircle(x, y - 34f, 16f, p)
        }
    }

    private fun drawBike(c: Canvas, x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y - 40f)
            lineTo(x - 26f, y + 22f)
            lineTo(x, y + 10f)
            lineTo(x + 26f, y + 22f)
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0); maskFilter = android.graphics.BlurMaskFilter(14f, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    // ---- Moments --------------------------------------------------------------------------

    /** The clip's frame at [atMs], upright. */
    private fun clipFrame(r: MediaMetadataRetriever, atMs: Long): Bitmap? = runCatching {
        val f = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot % 180 != 0 && f.width > f.height) {
            Bitmap.createBitmap(f, 0, 0, f.width, f.height, Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it !== f) f.recycle() }
        } else {
            f
        }
    }.getOrNull()

    /** The big centre card: 2/3 of the width, 9:16, white border, a label under it. */
    private fun drawCard(c: Canvas, picture: Bitmap?, label: String) {
        val w = W * 2f / 3f
        val h = w * 16f / 9f
        val left = (W - w) / 2
        val top = H * 0.46f - h / 2
        val rect = RectF(left, top, left + w, top + h)
        val r = 36f
        c.drawRoundRect(RectF(rect).apply { offset(0f, 18f) }, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(40f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        c.save()
        val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawColor(Color.rgb(20, 20, 22))
        if (picture != null) {
            // Centre-crop into the card.
            val scale = maxOf(w / picture.width, h / picture.height)
            val dw = picture.width * scale
            val dh = picture.height * scale
            c.drawBitmap(picture, null, RectF(left + (w - dw) / 2, top + (h - dh) / 2, left + (w + dw) / 2, top + (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        c.restore()
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE })
        if (label.isNotBlank()) {
            c.drawText(label, W / 2f, rect.bottom + 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                setShadowLayer(10f, 0f, 2f, Color.argb(160, 0, 0, 0))
            })
        }
    }

    /** The sound track's format: the first included clip's audio, or none. */
    private fun audioFormatOf(plan: RideVideoPlan, files: Map<String, File>): MediaFormat? {
        for (p in plan.parts) {
            if (p !is VideoPart.Hold || !p.moment.isClip) continue
            val f = files[p.moment.id] ?: continue
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.path)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return fmt
                }
            } catch (e: Exception) {
                Log.w(TAG, "No audio in ${f.name}", e)
            } finally {
                ex.release()
            }
        }
        return null
    }

    // ---- Encoding -------------------------------------------------------------------------

    /**
     * H.264 through the encoder's input surface; frames get evenly spaced timestamps (the
     * render is slower than real time). Clip audio is copied as is, at the hold's time.
     */
    private class Encoder(output: File, private val audioFormat: MediaFormat?) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val surface: android.view.Surface
        private val info = MediaCodec.BufferInfo()
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private var outFrames = 0L
        private val pendingAudio = ArrayList<Triple<java.nio.ByteBuffer, MediaCodec.BufferInfo, Unit>>()
        private var lastAudioUs = -1L

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // In-order output, so frames can be stamped by count.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        }

        fun addFrame(frame: Bitmap) {
            val c = surface.lockHardwareCanvas()
            try {
                c.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
                c.drawBitmap(frame, 0f, 0f, null)
            } finally {
                surface.unlockCanvasAndPost(c)
            }
            drain(false)
        }

        fun copyAudio(file: File, fromMs: Long, toMs: Long, startOutMs: Long) {
            if (audioFormat == null) return
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.path)
                val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
                ex.selectTrack(track)
                ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buf = java.nio.ByteBuffer.allocate(256 * 1024)
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = ex.sampleTime
                    if (t > toMs * 1000) break
                    if (t >= fromMs * 1000) {
                        val pts = startOutMs * 1000 + (t - fromMs * 1000)
                        if (pts > lastAudioUs) {
                            val copy = java.nio.ByteBuffer.allocate(size).apply { put(buf.array(), 0, size); flip() }
                            val bi = MediaCodec.BufferInfo().apply { set(0, size, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
                            lastAudioUs = pts
                            if (started) muxer.writeSampleData(audioTrack, copy, bi) else pendingAudio += Triple(copy, bi, Unit)
                        }
                    }
                    buf.clear()
                    ex.advance()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't copy the sound of ${file.name}", e)
            } finally {
                ex.release()
            }
        }

        private fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
                        muxer.start()
                        started = true
                        pendingAudio.forEach { (b, bi, _) -> muxer.writeSampleData(audioTrack, b, bi) }
                        pendingAudio.clear()
                    }
                    i >= 0 -> {
                        val out = codec.getOutputBuffer(i)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && started) {
                            info.presentationTimeUs = outFrames * 1_000_000L / FPS
                            outFrames++
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, out, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish() {
            codec.signalEndOfInputStream()
            drain(true)
            release()
        }

        fun abort() = runCatching { release() }

        private var released = false
        private fun release() {
            if (released) return
            released = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    companion object {
        private const val TAG = "RideVideo"
        const val W = 1080
        const val H = 1920
        const val FPS = 24
        private const val RATIO = 2
        private const val FOLLOW_ZOOM = 16.6
        private const val FOLLOW_TILT = 60.0
        private const val FOLLOW_TOP_PAD = 0.4
        private const val MAX_SEGMENTS = 700
        private const val SNAPSHOT_TIMEOUT_MS = 20_000L
        private const val ROUTE_SOURCE = "video-route"
        private val BACKGROUND = Color.rgb(13, 14, 16)

        /** Teal when cruising, amber, then red toward the ride's top speed. */
        fun heat(f: Float): Int {
            val t = f.coerceIn(0f, 1f)
            return if (t < 0.5f) lerp(Color.rgb(105, 200, 203), Color.rgb(251, 191, 36), t * 2) else lerp(Color.rgb(251, 191, 36), Color.rgb(244, 63, 94), (t - 0.5f) * 2)
        }

        private fun lerp(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        private fun hex(c: Int) = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and c)

        /** Turn from [from] toward [to] (degrees) by [k] of the shortest way. */
        fun easeAngle(from: Float, to: Float, k: Float): Float {
            val d = ((to - from) % 360f + 540f) % 360f - 180f
            return (from + d * k + 360f) % 360f
        }
    }
}
