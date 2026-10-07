package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.net.Uri
import android.util.Log
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
import androidx.media3.transformer.InAppMuxer
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
    /** Each clip's video by moment id: a moment's file, or a gallery video's content URI. */
    val files: Map<String, Uri>,
    val card: RideCard,
    val options: StudioOptions,
    /** Speed (km/h) and clock text at a wall time. */
    val speedAt: (Long) -> Int,
    val clockAt: (Long) -> String,
    /** The rider's own song; null = no music. */
    val music: Uri?,
    /** The song's level while the rider talks (0..1). */
    val duck: Float = 0.25f,
    /** Title label and hook line on the first clip. */
    val opener: StudioArt.Opener? = null,
    /** The voice-over as one track as long as the Reel; its captions and where it talks, in Reel ms. */
    val voice: File? = null,
    val voiceLines: List<CaptionLine> = emptyList(),
)

/**
 * Makes the Reel with Media3 Transformer: each segment is a part of a clip, cropped to
 * 1080×1920, with its camera move, the vibe's colour grade and the drawn graphics on top.
 * The title and stats play over a clip with its sound off. The song, if any, is a second
 * track that dips while the rider talks.
 */
@UnstableApi
class StudioRenderer(private val context: Context) {

    /** The finished file, a note when it had to leave something out, and the attempts that failed on the way. */
    data class Rendered(val file: File, val note: String?, val failures: List<Throwable> = emptyList())

    /** Every export attempt failed; each attempt's error is attached (suppressed) with its stack trace. */
    class ExportFailed(message: String) : Exception(message)

    /** One way of exporting; later ones leave things out, in case a phone's encoder or muxer chokes. */
    private data class Attempt(val inAppMuxer: Boolean, val extraTracks: Boolean, val sound: Boolean, val note: String?)

    /**
     * Tries Media3's own muxer first (it copes with odd timestamps), then the phone's, then
     * without the song and voice-over tracks, then without sound. Fails with every error's reason.
     */
    suspend fun render(input: RenderInput, output: File, onProgress: (Int) -> Unit): Result<Rendered> {
        val extras = input.music != null || input.voice != null
        val attempts = listOfNotNull(
            Attempt(inAppMuxer = true, extraTracks = true, sound = true, note = null),
            Attempt(inAppMuxer = false, extraTracks = true, sound = true, note = null),
            if (extras) Attempt(inAppMuxer = true, extraTracks = false, sound = true, note = "Made without the song and voice-over: this phone couldn't mix them in.") else null,
            Attempt(inAppMuxer = true, extraTracks = false, sound = false, note = "Made without sound: this phone couldn't write the sound track. Send me the error below."),
        )
        val reasons = ArrayList<String>()
        val failures = ArrayList<Throwable>()
        for (a in attempts) {
            val r = export(input, a, output, onProgress)
            r.onSuccess { return Result.success(Rendered(it, a.note?.let { n -> if (a.sound) n else "$n (${reasons.joinToString(" / ")})" }, failures)) }
            val e = r.exceptionOrNull()!!
            if (e is kotlinx.coroutines.CancellationException) throw e
            reasons += describe(e)
            failures += IllegalStateException("Attempt ${failures.size + 1} ($a) failed: ${describe(e)}", e)
            Log.w(TAG, "Export attempt $a failed", e)
            onProgress(0)
        }
        val all = ExportFailed(reasons.distinct().joinToString(" / "))
        failures.forEach { all.addSuppressed(it) }
        return Result.failure(all)
    }

    /** "Muxer error (ERROR_CODE_MUXING_FAILED: Failed to write sample …)" — enough to find the cause. */
    private fun describe(e: Throwable): String {
        val code = (e as? ExportException)?.errorCodeName
        val causes = generateSequence(e.cause) { it.cause }.take(3).mapNotNull { it.message?.take(120) }.toList()
        return listOfNotNull(e.message, code, causes.joinToString(": ").ifBlank { null }).joinToString(" · ")
    }

    private suspend fun export(input: RenderInput, a: Attempt, output: File, onProgress: (Int) -> Unit): Result<File> {
        output.delete()
        val composition = runCatching { composition(input, a) }.getOrElse { return Result.failure(it) }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .apply { if (a.inAppMuxer) setMuxerFactory(InAppMuxer.Factory.Builder().build()) }
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

    private fun composition(input: RenderInput, a: Attempt): Composition {
        val plan = input.plan
        val segs = plan.segments
        val clips = plan.clips
        require(clips.isNotEmpty()) { "No clips to use" }
        val art = StudioArt(context)
        val items = segs.mapIndexed { i, seg -> item(input, art, i, seg, a.sound) }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        if (!a.sound) return Composition.Builder(sequences).build()
        if (a.extraTracks) input.voice?.let { wav ->
            sequences += EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build()))
        }
        if (a.extraTracks) input.music?.let { uri ->
            val duck = GainProcessor.ducking(plan.talkingRanges() + voiceRanges(input), input.duck)
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
    private fun item(input: RenderInput, art: StudioArt, i: Int, seg: Segment, sound: Boolean): EditedMediaItem {
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
        val uri = input.files[clip.bit.momentId] ?: clip.bit.source?.let(Uri::parse) ?: error("Clip file missing")
        val media = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(from + dur).build())
            .build()

        val clock = ItemClock()
        val segStart = plan.startOf(i)
        // Voice-over captions that fall in this segment, timed from its start.
        val voLines = input.voiceLines.filter { it.endMs > segStart && it.startMs < segStart + dur }.map { it.copy(startMs = it.startMs - segStart, endMs = it.endMs - segStart) }
        val lines = if (seg is ClipSegment) (seg.lines + voLines).sortedBy { it.startMs } else voLines
        val opener = input.opener.takeIf { seg is ClipSegment && seg.hook && !seg.tail }
        val frameAt = { localMs: Long ->
            FrameAt(
                vibe = plan.vibe,
                localMs = localMs,
                durMs = dur,
                // Inside a script section the cut is plain; the vibe's transition plays between sections.
                hasPrev = i > 0 && boundary(segs[i - 1], seg),
                hasNext = i < segs.lastIndex && boundary(seg, segs[i + 1]),
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
                    // Clips from other rides have no samples here: their own speed.
                    // No speed for that moment (a GPS gap, another ride, a phone video): the bit's own
                    // speed if it has one, else no badge (-1) rather than a wrong "0".
                    val kmh = input.speedAt(wall).takeIf { it >= 0 && seg.bit.fromRide == null }
                        ?: seg.bit.speedKmh.toInt().takeIf { it > 0 } ?: -1
                    art.drawClip(c, f, if (seg.tail) emptyList() else lines, kmh, input.clockAt(wall), o.captions, opener)
                    seg.text?.let { t -> art.drawSectionText(c, plan.vibe, t, localMs) }
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

        // Talking clips at full volume, riding noise softer, the title and stats silent; short fades at the
        // cuts; the clip dips under the voice-over.
        val level = when (seg) {
            is ClipSegment -> if (seg.lines.isNotEmpty()) 1f else 0.55f
            else -> 0f
        }
        val underVoice = GainProcessor.ducking(voiceRanges(input).map { (it.first - segStart)..(it.last - segStart) }, 0.3f)
        val audio = GainProcessor { us -> level * fade(us / 1000, dur, 60) * underVoice(us) }
        if (!sound) return EditedMediaItem.Builder(media).setRemoveAudio(true).setEffects(Effects(emptyList(), video)).build()
        return EditedMediaItem.Builder(media).setEffects(Effects(listOf(audio), video)).build()
    }

    /** Where the voice-over talks, in Reel ms. */
    private fun voiceRanges(input: RenderInput): List<LongRange> = input.voiceLines.map { (it.startMs - 150)..(it.endMs + 250) }

    /** What a transition says about segment [s]: speed and time for a clip, "That's a wrap" for the stats. */
    /** A transition between [a] and [b]: always, unless both are clips of the same script section. */
    private fun boundary(a: Segment, b: Segment): Boolean =
        !(a is ClipSegment && b is ClipSegment && a.section >= 0 && a.section == b.section && !b.tail)

    private fun describe(input: RenderInput, s: Segment?): Pair<String, String> = when (s) {
        is ClipSegment -> {
            val kmh = input.speedAt(s.bit.atMillis).takeIf { it >= 0 && s.bit.fromRide == null } ?: s.bit.speedKmh.toInt().takeIf { it > 0 } ?: -1
            // No speed known: just its time.
            if (kmh < 0) input.clockAt(s.bit.atMillis) to "" else "$kmh km/h" to (s.bit.fromRide ?: input.clockAt(s.bit.atMillis))
        }
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
        const val TAG = "Studio"
        const val MUSIC_LEVEL = 0.7f

        /** 0 → 1 over [ms] at the start and 1 → 0 before [total]. */
        fun fade(t: Long, total: Long, ms: Long = 600): Float =
            minOf(1f, t.toFloat() / ms, (total - t).toFloat() / ms).coerceIn(0f, 1f)
    }
}
