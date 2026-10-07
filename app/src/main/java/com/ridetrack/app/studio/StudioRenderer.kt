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
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
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
    /** Size, frame rate and bitrate to write; null = the Reel as usual (1080p, 30 fps). */
    val output: OutputSpec? = null,
    /** Lean (degrees, + right) at a wall time; null where it isn't known. */
    val leanAt: (Long) -> Int? = { null },
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

    /** How layers are made: combined by Media3, drawn frame by frame (slower, any phone), or there are none. */
    private enum class LayerMode { COMPOSITE, DRAWN, NONE }

    /** One way of exporting; later ones leave things out, in case a phone's encoder or muxer chokes. */
    private data class Attempt(val inAppMuxer: Boolean, val extraTracks: Boolean, val sound: Boolean, val note: String?, val layers: LayerMode = LayerMode.NONE)

    /** The drawn layers of the export in progress (released when it ends). */
    private var drawer: LayerDrawer? = null

    /**
     * Tries Media3's own muxer first (it copes with odd timestamps), then the phone's, then
     * without the song and voice-over tracks, then without sound. Fails with every error's reason.
     */
    suspend fun render(input: RenderInput, output: File, onProgress: (Int) -> Unit): Result<Rendered> {
        val plan = input.plan
        val extras = input.music != null || input.voice != null || plan.audio.isNotEmpty() || plan.layers.any { it.volume > 0f }
        val hasLayers = plan.layers.isNotEmpty()
        val first = if (hasLayers) LayerMode.COMPOSITE else LayerMode.NONE
        val simple = if (hasLayers) LayerMode.DRAWN else LayerMode.NONE
        val attempts = listOfNotNull(
            Attempt(inAppMuxer = true, extraTracks = true, sound = true, note = null, layers = first),
            Attempt(inAppMuxer = false, extraTracks = true, sound = true, note = null, layers = first),
            // Layers drawn frame by frame: slower, but any phone can.
            if (hasLayers) Attempt(inAppMuxer = true, extraTracks = true, sound = true, note = "Made with a simpler method on this phone (layers drawn frame by frame).", layers = LayerMode.DRAWN) else null,
            if (extras) Attempt(inAppMuxer = true, extraTracks = false, sound = true, note = "Made without the extra sound tracks (song, voice-over, engine, detached sound): this phone couldn't mix them in.", layers = simple) else null,
            Attempt(inAppMuxer = true, extraTracks = false, sound = false, note = "Made without sound: this phone couldn't write the sound track. Send me the error below.", layers = simple),
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
                    .apply {
                        input.output?.let { o ->
                            setEncoderFactory(
                                androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                                    .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(o.bitrate).build())
                                    .setEnableFallback(true)
                                    .build(),
                            )
                        }
                    }
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
        }.also {
            drawer?.release()
            drawer = null
        }
    }

    /** True while building the editor's preview (frames may start anywhere). */
    private var preview = false

    /**
     * The edit as the made video would be, for the editor's exact preview: graphics, colour,
     * camera moves and every sound track. Layers aren't in it (the editor shows them as stills).
     */
    fun previewComposition(input: RenderInput): Composition {
        preview = true
        return composition(input, Attempt(inAppMuxer = true, extraTracks = true, sound = true, note = null, layers = LayerMode.NONE))
    }

    private fun composition(input: RenderInput, a: Attempt): Composition {
        val plan = input.plan
        val segs = plan.segments
        val clips = plan.clips
        require(clips.isNotEmpty()) { "No clips to use" }
        val art = StudioArt(context)
        val mix = plan.mix
        drawer?.release()
        drawer = if (a.layers == LayerMode.DRAWN) LayerDrawer(context, plan.layers) { l -> uriOf(input, l.bit) } else null
        val items = segs.mapIndexed { i, seg -> item(input, art, i, seg, a.sound) }
        val sequences = mutableListOf<EditedMediaItemSequence>()
        // Layers first (the first is drawn on top; the newest layer is on top), then the main video.
        val stacked = plan.layers.asReversed()
        if (a.layers == LayerMode.COMPOSITE) stacked.forEach { sequences += layerSequence(input, art, it) }
        sequences += EditedMediaItemSequence(items)
        fun build(): Composition = Composition.Builder(sequences)
            .apply { if (a.layers == LayerMode.COMPOSITE) setVideoCompositorSettings(LayerCompositor(stacked, art.w, art.h)) }
            .apply { input.output?.let { o -> setEffects(Effects(emptyList(), outputEffects(o))) } }
            .apply { if (a.sound) experimentalSetForceAudioTrack(true) }
            .build()
        if (!a.sound || !a.extraTracks) return build()
        val total = plan.totalMs
        val talk = plan.talkingRanges() + voiceRanges(input)
        input.voice?.let { wav ->
            val g = mix.gain(TrackKind.VOICE_OVER)
            sequences += EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).setEffects(Effects(listOf(GainProcessor { g }), emptyList())).build()))
        }
        input.music?.let { uri ->
            val duck = if (mix.duck) GainProcessor.ducking(talk, input.duck) else { _ -> 1f }
            val g = mix.gain(TrackKind.MUSIC)
            val music = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setRemoveVideo(true)
                // The song fades in and out at the ends, and dips while the rider talks.
                .setEffects(Effects(listOf(GainProcessor { us -> MUSIC_LEVEL * g * duck(us) * fade(us / 1000, total) }), emptyList()))
                .build()
            sequences += EditedMediaItemSequence(listOf(music), true)
        }
        // Detached sounds, the engine mic, and layers' own sound, each lined up on its own track.
        val engineDuck = if (mix.duck) GainProcessor.ducking(talk, 0.35f) else { _ -> 1f }
        plan.audio.forEach { au ->
            val g = mix.gain(au.kind)
            if (g == 0f) return@forEach
            val uri = au.file?.let(Uri::parse) ?: uriOf(input, au.bit) ?: return@forEach
            val ducked = au.kind == TrackKind.ENGINE
            sequences += audioSequence(au.startMs, uri, au.inMs, au.durMs, clean = mix.cleanVoice && au.kind == TrackKind.DETACHED) { local ->
                TrackEdits.levelAt(au, local) * g * if (ducked) engineDuck((au.startMs + local) * 1000) else 1f
            }
        }
        plan.layers.filter { it.volume > 0f }.forEach { l ->
            val g = mix.gain(TrackKind.LAYERS) * l.volume
            val uri = uriOf(input, l.bit) ?: return@forEach
            if (g > 0f) sequences += audioSequence(l.startMs, uri, l.inMs, l.durMs, clean = false) { local -> g * fade(local, l.durMs, 80) }
        }
        return build()
    }

    /** The export's own size and frame rate on the finished picture. */
    private fun outputEffects(o: OutputSpec): List<Effect> = listOfNotNull(
        if (o.width != 1080 || o.height != 1920) Presentation.createForWidthAndHeight(o.width, o.height, Presentation.LAYOUT_SCALE_TO_FIT) else null,
        if (o.fps <= 30) androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(o.fps.toFloat()) else null,
    )

    private fun uriOf(input: RenderInput, b: Bit): Uri? = input.files[b.momentId] ?: b.source?.let(Uri::parse)

    /** A layer's own video track: clear until it starts, the clip, then clear to the end. */
    private fun layerSequence(input: RenderInput, art: StudioArt, l: LayerItem): EditedMediaItemSequence {
        val (w, h) = layerSize(l, art.w)
        val fx: List<Effect> = listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)) + grade(input.plan.vibe) + MaskEffect(l.shape, l.border)
        val uri = uriOf(input, l.bit) ?: error("Layer clip missing")
        val items = ArrayList<EditedMediaItem>()
        if (l.startMs > 0) items += filler(l.startMs, fx)
        items += EditedMediaItem.Builder(clipped(uri, l.inMs, l.inMs + l.durMs)).setRemoveAudio(true).setEffects(Effects(emptyList(), fx)).build()
        val after = input.plan.totalMs - l.endMs
        if (after > 0) items += filler(after, fx)
        return EditedMediaItemSequence(items)
    }

    private fun filler(ms: Long, fx: List<Effect>): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(Fillers.picture(context))).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(ms).build())
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), fx))
            .build()

    private fun clipped(uri: Uri, fromMs: Long, toMs: Long): MediaItem = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).setEndPositionMs(toMs).build())
        .build()

    /** A sound on its own track: silence until [startMs], then [durMs] of [uri] from [inMs], at [level] (ms from its start). */
    private fun audioSequence(startMs: Long, uri: Uri, inMs: Long, durMs: Long, clean: Boolean, level: (Long) -> Float): EditedMediaItemSequence {
        val items = ArrayList<EditedMediaItem>()
        if (startMs > 0) items += EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(Fillers.silence(context, startMs)))).build()
        val procs = listOfNotNull<androidx.media3.common.audio.AudioProcessor>(HighPassProcessor().takeIf { clean }, GainProcessor { us -> level(us / 1000) })
        items += EditedMediaItem.Builder(clipped(uri, inMs, inMs + durMs)).setRemoveVideo(true).setEffects(Effects(procs, emptyList())).build()
        return EditedMediaItemSequence(items)
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
        val cs = (seg as? ClipSegment)?.takeIf { !it.tail }
        // How much of the clip plays (more than the segment when sped up), and how long it shows.
        val srcLen = when {
            cs?.still != null -> 0L
            cs != null -> cs.sourceMs.coerceAtMost(clip.bit.clipDurationMs - from).coerceAtLeast(300)
            else -> seg.durMs.coerceAtMost(clip.bit.clipDurationMs - from).coerceAtLeast(500)
        }
        val dur = when {
            cs?.still != null -> cs.durMs
            cs != null -> Speed.outMs(srcLen, cs.speed, cs.ramp)
            else -> srcLen
        }
        val uri = input.files[clip.bit.momentId] ?: clip.bit.source?.let(Uri::parse) ?: error("Clip file missing")
        val media = when {
            // A freeze frame: the picture held.
            cs?.still != null -> MediaItem.Builder().setUri(Uri.fromFile(File(cs.still))).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(dur).build()
            // Backwards: the reversed copy made when Reverse was tapped.
            cs?.reverse != null && File(cs.reverse).isFile -> clipped(Uri.fromFile(File(cs.reverse)), 0, srcLen)
            else -> clipped(uri, from, from + srcLen)
        }
        val sped = cs != null && cs.still == null && (cs.speed != 1f || cs.ramp != SpeedRamp.NONE)

        val segStart = plan.startOf(i)
        val clock = ItemClock(segStart, from, dur, preview)
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
                // A transition the rider chose on a cut always plays (a Cut never does).
                hasPrev = i > 0 && (cs?.transition?.let { it.kind != TransitionKind.CUT } ?: boundary(segs[i - 1], seg)),
                hasNext = i < segs.lastIndex && ((segs[i + 1] as? ClipSegment)?.transition?.let { it.kind != TransitionKind.CUT } ?: boundary(seg, segs[i + 1])),
                inKind = cs?.transition?.kind ?: TransitionKind.STYLE,
                inMs = cs?.transition?.ms(plan.vibe) ?: plan.vibe.transitionMs,
                outKind = (segs.getOrNull(i + 1) as? ClipSegment)?.transition?.kind ?: TransitionKind.STYLE,
                outMs = (segs.getOrNull(i + 1) as? ClipSegment)?.transition?.ms(plan.vibe) ?: plan.vibe.transitionMs,
                nextLabel = describe(input, segs.getOrNull(if (localMs < dur / 2) i else i + 1)).first,
                nextTime = describe(input, segs.getOrNull(if (localMs < dur / 2) i else i + 1)).second,
            )
        }
        val clipStart = clip.bit.atMillis - clip.bit.inMs
        val overlay = SegmentOverlay(overlayBitmap(art.w, art.h), clock) { c, localMs ->
            val f = frameAt(localMs)
            var wallNow: Long? = null
            var kmhNow = -1
            when (seg) {
                is ClipSegment -> {
                    // The clip's own time runs at its speed; a freeze holds its moment.
                    val wall = clipStart + from + if (cs?.still != null) 0 else localMs * srcLen / dur.coerceAtLeast(1)
                    // Clips from other rides have no samples here: their own speed.
                    // No speed for that moment (a GPS gap, another ride, a phone video): the bit's own
                    // speed if it has one, else no badge (-1) rather than a wrong "0".
                    val kmh = input.speedAt(wall).takeIf { it >= 0 && seg.bit.fromRide == null }
                        ?: seg.bit.speedKmh.toInt().takeIf { it > 0 } ?: -1
                    wallNow = wall
                    kmhNow = kmh
                    // The corner speed is off in some styles (a speed sticker may show it instead).
                    art.drawClip(c, f, if (seg.tail) emptyList() else lines, if (o.speedBadge) kmh else -1, input.clockAt(wall), o.captions, opener, plan.captionLook)
                    seg.text?.let { t -> art.drawSectionText(c, plan.vibe, t, localMs) }
                }
                is TitleSegment -> art.drawTitle(c, f, input.card, o.map)
                is StatsSegment -> art.drawStats(c, f, input.card, o.map, o.watermark)
            }
            val global = segStart + localMs
            // Layers drawn frame by frame (the slower way), under the text.
            drawer?.draw(c, global)
            // Text and stickers the rider placed on the timeline, in Reel time.
            plan.texts.forEach { t -> if (global in t.startMs until t.endMs) art.drawTextItem(c, plan.vibe, t, global - t.startMs, t.endMs - t.startMs) }
            plan.stickers.forEach { st -> if (global in st.startMs until st.endMs) art.drawSticker(c, st, global - st.startMs, kmhNow, wallNow?.let(input.leanAt)) }
        }
        val video = ArrayList<Effect>()
        // Speed first, so everything after sees the Reel's own time.
        if (sped) video += SpeedChangeEffect(RampSpeed(cs!!.speed, cs.ramp, srcLen * 1000))
        if (cs != null && (cs.rotation != 0 || cs.flip)) {
            video += ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - cs.rotation).toFloat()).setScale(if (cs.flip) -1f else 1f, 1f).build()
        }
        video += Presentation.createForWidthAndHeight(art.w, art.h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        if (seg is ClipSegment) {
            video += MatrixTransformation { us ->
                val local = clock.localMs(us)
                art.cameraMatrix(reframed(art, art.camera(frameAt(local)), cs, local))
            }
        } else if (seg is TitleSegment && !o.map) {
            video += MatrixTransformation { us -> Matrix().apply { val k = 1.12f - 0.05f * clock.localMs(us) / dur; setScale(k, k) } }
        }
        if (cs == null || cs.color.look) video += grade(plan.vibe)
        if (cs != null && !cs.color.plain) video += colour(cs.color)
        video += OverlayEffect(listOf(overlay))

        // Talking clips at full volume, riding noise softer, the title and stats silent; short fades at the
        // cuts; the clip dips under the voice-over.
        val level = when (seg) {
            is ClipSegment -> (if (seg.lines.isNotEmpty()) 1f else 0.55f) * seg.volume
            else -> 0f
        }
        val underVoice = GainProcessor.ducking(voiceRanges(input).map { (it.first - segStart)..(it.last - segStart) }, 0.3f)
        val g = plan.mix.gain(TrackKind.CLIPS)
        val audio = GainProcessor { us -> level * g * fade(us / 1000, dur, 60) * underVoice(us) }
        // A still or a reversed copy has no sound of its own (silence fills in).
        val silent = cs?.still != null || (cs?.reverse != null && File(cs.reverse).isFile)
        if (!sound || silent) {
            return EditedMediaItem.Builder(media).setRemoveAudio(true).apply { if (cs?.still != null) setFrameRate(30) }.setEffects(Effects(emptyList(), video)).build()
        }
        // Sped up or slowed: the sound at the same speed. The voice clean-up on talking clips.
        val procs = listOfNotNull(
            if (sped) SpeedChangingAudioProcessor(RampSpeed(cs!!.speed, cs.ramp, srcLen * 1000)) else null,
            if (plan.mix.cleanVoice && seg is ClipSegment && seg.lines.isNotEmpty()) HighPassProcessor() else null,
            audio,
        )
        return EditedMediaItem.Builder(media).setEffects(Effects(procs, video)).build()
    }

    /** The style's camera with the rider's zoom and pan on top. */
    private fun reframed(art: StudioArt, cam: Cam, cs: ClipSegment?, localMs: Long): Cam {
        if (cs == null || cs.frame.isEmpty()) return cam
        val f = ClipTools.frameAt(cs, localMs)
        val room = (f.zoom - 1f) / 2f
        return cam.copy(k = cam.k * f.zoom, x = cam.x - f.x * art.w * room, y = cam.y - f.y * art.h * room)
    }

    /** A clip's own colour: exposure, contrast, saturation, warmth. */
    private fun colour(c: ClipColor): List<Effect> = listOfNotNull(
        if (c.exposure != 0f) Brightness(c.exposure * 0.35f) else null,
        if (c.contrast != 0f) Contrast(c.contrast * 0.4f) else null,
        if (c.saturation != 0f) HslAdjustment.Builder().adjustSaturation(c.saturation * 40f).build() else null,
        if (c.warmth != 0f) RgbAdjustment.Builder().setRedScale(1f + 0.12f * c.warmth).setBlueScale(1f - 0.12f * c.warmth).build() else null,
    )

    /**
     * The speed over a clip's part ([lengthUs] long): constant, or in a ramp's steps. Times are
     * from the first frame or sample it sees.
     */
    private class RampSpeed(private val speed: Float, private val ramp: SpeedRamp, private val lengthUs: Long) : androidx.media3.common.audio.SpeedProvider {
        @Volatile private var base: Long? = null

        private fun origin(timeUs: Long): Long = base ?: synchronized(this) { base ?: timeUs.also { base = it } }

        override fun getSpeed(timeUs: Long): Float {
            val b = origin(timeUs)
            val f = ((timeUs - b).toFloat() / lengthUs.coerceAtLeast(1)).coerceIn(0f, 1f)
            return Speed.at(speed, ramp, f)
        }

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            val b = origin(timeUs)
            return ramp.steps.dropLast(1).map { b + (it.first * lengthUs).toLong() }.firstOrNull { it > timeUs } ?: androidx.media3.common.C.TIME_UNSET
        }
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
    /**
     * Ms into a segment from a frame's timestamp. Frames can be stamped from the composition's
     * start, the item's start, or the clip's own time; in a made video the first frame shows
     * which (it's the segment's first), and that's remembered for the preview, which can start
     * anywhere.
     */
    private inner class ItemClock(private val segStartMs: Long, private val fromMs: Long, private val durMs: Long, private val preview: Boolean) {
        @Volatile private var mode = -1
        @Volatile private var firstUs: Long? = null

        fun localMs(us: Long): Long {
            val t = us / 1000
            val cand = longArrayOf(t - segStartMs, t, t - fromMs)
            if (!preview) {
                // Exporting: the first frame is the segment's first.
                val first = firstUs ?: synchronized(this) { firstUs ?: us.also { f -> firstUs = f; learn(cand) } }
                return ((us - first) / 1000).coerceIn(0, durMs)
            }
            var m = mode
            if (m < 0) {
                m = frameTimeMode(context).takeIf { it >= 0 } ?: (cand.indices.firstOrNull { cand[it] in 0..durMs } ?: 0)
                mode = m
            }
            return cand[m].coerceIn(0, durMs)
        }

        private fun learn(cand: LongArray) {
            cand.indices.firstOrNull { kotlin.math.abs(cand[it]) <= 80 }?.let { setFrameTimeMode(context, it) }
        }
    }

    /** A transparent frame-sized bitmap, redrawn for every frame. */
    /**
     * One overlay picture shared by every segment: segments play one after another, so they never
     * draw at the same time (a 5-minute video has dozens of segments; one 1080×1920 picture each
     * would run out of memory).
     */
    private var shared: Bitmap? = null

    private fun overlayBitmap(w: Int, h: Int): Bitmap =
        shared?.takeIf { it.width == w && it.height == h && !it.isRecycled } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { shared = it }

    private class SegmentOverlay(private val bitmap: Bitmap, private val clock: ItemClock, private val draw: (Canvas, Long) -> Unit) : BitmapOverlay() {
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

        /** How frames are stamped (0 composition, 1 item, 2 clip time), learnt from a made video; -1 = not yet. */
        fun frameTimeMode(context: Context): Int = context.getSharedPreferences("studio", Context.MODE_PRIVATE).getInt("frame_time_mode", -1)

        fun setFrameTimeMode(context: Context, m: Int) {
            context.getSharedPreferences("studio", Context.MODE_PRIVATE).edit().putInt("frame_time_mode", m).apply()
        }

        /** 0 → 1 over [ms] at the start and 1 → 0 before [total]. */
        fun fade(t: Long, total: Long, ms: Long = 600): Float =
            minOf(1f, t.toFloat() / ms, (total - t).toFloat() / ms).coerceIn(0f, 1f)
    }
}
