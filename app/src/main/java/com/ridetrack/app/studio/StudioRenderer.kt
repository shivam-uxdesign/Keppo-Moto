package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Everything the video needs besides the plan. */
data class RenderInput(
    val plan: StudioPlan,
    /** Each moment's clip file, by moment id. */
    val files: Map<String, File>,
    val card: RideCard,
    val options: StudioOptions,
    /** Speed (km/h) and clock text at a wall time. */
    val speedAt: (Long) -> Int,
    val clockAt: (Long) -> String,
    /** The rider's own song; null = no music. */
    val music: Uri?,
    /** The song's level while the rider talks (0..1). */
    val duck: Float = 0.25f,
)

/**
 * Makes the Reel with Media3 Transformer: each segment is a part of a clip, cropped to
 * 1080×1920, with its camera move, the vibe's colour grade and the drawn graphics on top.
 * The title and stats play over a clip with its sound off. The song, if any, is a second
 * track that dips while the rider talks.
 */
@UnstableApi
class StudioRenderer(private val context: Context) {

    suspend fun render(input: RenderInput, output: File, onProgress: (Int) -> Unit): Result<File> {
        output.delete()
        val composition = runCatching { composition(input) }.getOrElse { return Result.failure(it) }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Result.success(output))
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            output.delete()
                            if (cont.isActive) cont.resume(Result.failure(exportException))
                        }
                    })
                    .build()
                transformer.start(composition, output.path)
                val poll = CoroutineScope(Dispatchers.Main).launch {
                    val holder = ProgressHolder()
                    while (cont.isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        delay(200)
                    }
                }
                cont.invokeOnCancellation {
                    poll.cancel()
                    transformer.cancel()
                    output.delete()
                }
            }
        }
    }

    private fun composition(input: RenderInput): Composition {
        val plan = input.plan
        val segs = plan.segments
        val clips = plan.clips
        require(clips.isNotEmpty()) { "No clips to use" }
        val art = StudioArt(context)
        val items = segs.mapIndexed { i, seg -> item(input, art, i, seg) }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        input.music?.let { uri ->
            val duck = GainProcessor.ducking(plan.talkingRanges(), input.duck)
            val total = plan.totalMs
            val music = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setRemoveVideo(true)
                // The song fades in and out at the ends, and dips while the rider talks.
                .setEffects(Effects(listOf(GainProcessor { us -> MUSIC_LEVEL * duck(us) * fade(us / 1000, total) }), emptyList()))
                .build()
            sequences += EditedMediaItemSequence(listOf(music), true)
        }
        return Composition.Builder(sequences).experimentalSetForceAudioTrack(true).build()
    }

    /** One segment: which clip and part of it, how it's moved and graded, and what's drawn on it. */
    private fun item(input: RenderInput, art: StudioArt, i: Int, seg: Segment): EditedMediaItem {
        val plan = input.plan
        val o = input.options
        val segs = plan.segments
        val firstClip = plan.clips.first()
        val lastClip = plan.clips.last()
        val (clip, from) = when (seg) {
            is ClipSegment -> seg to seg.inMs
            // The title plays over the moments just before the first clip (or its start), muted.
            is TitleSegment -> firstClip to if (firstClip.inMs >= seg.durMs) firstClip.inMs - seg.durMs else firstClip.inMs
            is StatsSegment -> lastClip to 0L
        }
        val dur = seg.durMs.coerceAtMost(clip.bit.clipDurationMs - from).coerceAtLeast(500)
        val file = input.files[clip.bit.momentId] ?: error("Clip file missing")
        val media = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(from + dur).build())
            .build()

        val clock = ItemClock()
        val frameAt = { localMs: Long ->
            FrameAt(
                vibe = plan.vibe,
                localMs = localMs,
                durMs = dur,
                hasPrev = i > 0,
                hasNext = i < segs.lastIndex,
                nextLabel = describe(input, segs.getOrNull(if (localMs < dur / 2) i else i + 1)).first,
                nextTime = describe(input, segs.getOrNull(if (localMs < dur / 2) i else i + 1)).second,
            )
        }
        val clipStart = clip.bit.atMillis - clip.bit.inMs
        val overlay = SegmentOverlay(art.w, art.h, clock) { c, localMs ->
            val f = frameAt(localMs)
            when (seg) {
                is ClipSegment -> {
                    val wall = clipStart + from + localMs
                    art.drawClip(c, f, seg, input.speedAt(wall), input.clockAt(wall), o.captions)
                }
                is TitleSegment -> art.drawTitle(c, f, input.card, o.map)
                is StatsSegment -> art.drawStats(c, f, input.card, o.map, o.watermark)
            }
        }
        val video = ArrayList<Effect>()
        video += Presentation.createForWidthAndHeight(art.w, art.h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        if (seg is ClipSegment) {
            video += MatrixTransformation { us -> art.cameraMatrix(art.camera(frameAt(clock.localMs(us)))) }
        } else if (seg is TitleSegment && !o.map) {
            video += MatrixTransformation { us -> Matrix().apply { val k = 1.12f - 0.05f * clock.localMs(us) / dur; setScale(k, k) } }
        }
        video += grade(plan.vibe)
        video += OverlayEffect(listOf(overlay))

        // Talking clips at full volume, riding noise softer, the title and stats silent; short fades at the cuts.
        val level = when (seg) {
            is ClipSegment -> if (seg.lines.isNotEmpty()) 1f else 0.55f
            else -> 0f
        }
        val audio = GainProcessor { us -> level * fade(us / 1000, dur, 60) }
        return EditedMediaItem.Builder(media).setEffects(Effects(listOf(audio), video)).build()
    }

    /** What a transition says about segment [s]: speed and time for a clip, "That's a wrap" for the stats. */
    private fun describe(input: RenderInput, s: Segment?): Pair<String, String> = when (s) {
        is ClipSegment -> "${input.speedAt(s.bit.atMillis)} km/h" to input.clockAt(s.bit.atMillis)
        is StatsSegment -> "That's a wrap" to input.card.title
        else -> "" to ""
    }

    /** Each vibe's colour look. */
    private fun grade(v: Vibe): List<Effect> = when (v) {
        Vibe.HYPE -> listOf(Contrast(0.1f), HslAdjustment.Builder().adjustSaturation(16f).build(), RgbAdjustment.Builder().setRedScale(1.04f).setBlueScale(0.96f).build())
        Vibe.CINE -> listOf(Contrast(0.12f), HslAdjustment.Builder().adjustSaturation(-20f).adjustLightness(-3f).build(), RgbAdjustment.Builder().setRedScale(0.96f).setBlueScale(1.05f).build())
        Vibe.CHILL -> listOf(HslAdjustment.Builder().adjustSaturation(-12f).adjustLightness(4f).build(), RgbAdjustment.Builder().setRedScale(1.06f).setBlueScale(0.92f).build())
        Vibe.VLOG -> listOf(Contrast(0.05f), HslAdjustment.Builder().adjustSaturation(8f).build())
    }

    /** Ms since the item's first frame (frames may be stamped from the clip start or the composition). */
    private class ItemClock {
        @Volatile private var firstUs: Long? = null
        fun localMs(us: Long): Long {
            val base = firstUs ?: synchronized(this) { firstUs ?: us.also { firstUs = it } }
            return ((us - base) / 1000).coerceAtLeast(0)
        }
    }

    /** A transparent frame-sized bitmap, redrawn for every frame. */
    private class SegmentOverlay(w: Int, h: Int, private val clock: ItemClock, private val draw: (Canvas, Long) -> Unit) : BitmapOverlay() {
        private val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            draw(canvas, clock.localMs(presentationTimeUs))
            return bitmap
        }
    }

    private companion object {
        const val MUSIC_LEVEL = 0.7f

        /** 0 → 1 over [ms] at the start and 1 → 0 before [total]. */
        fun fade(t: Long, total: Long, ms: Long = 600): Float =
            minOf(1f, t.toFloat() / ms, (total - t).toFloat() / ms).coerceIn(0f, 1f)
    }
}
