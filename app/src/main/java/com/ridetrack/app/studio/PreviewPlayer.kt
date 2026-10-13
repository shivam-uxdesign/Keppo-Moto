package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.image.ImageOutput
import java.io.File
import kotlin.math.abs

/**
 * The editor's one preview. The edit plays as a playlist (one item per segment: the clip's part
 * at its speed, its reversed copy, or a freeze frame) into the editor's picture, which draws the
 * look, the camera and the graphics on top, the same as the made video. A change only swaps the
 * playlist, which a normal player takes any number of times. The next clip is loaded before the
 * cut. The sound tracks besides the clips' own play from a mixed file on a second player, kept in
 * step. A playback error reloads where it was (three tries) and then says so; it never switches
 * to another preview.
 */
@UnstableApi
class EditorPreview(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ALL
        // The next clip is opened ahead, so cuts (and the transitions on them) play smoothly.
        setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PRELOAD_US))
    }

    /** The song, voice-over, engine and other sound tracks, mixed (see [StudioRenderer.soundMix]). */
    private val sound: ExoPlayer = ExoPlayer.Builder(context).build()

    /** A freeze frame being shown (drawn over the picture); null when a clip plays. */
    var still by mutableStateOf<Bitmap?>(null)
        private set

    /** Shown on the video when the preview couldn't play after three tries; null when fine. */
    var problem by mutableStateOf<String?>(null)
        private set

    private var plan: StudioPlan? = null
    private var files: Map<String, Uri> = emptyMap()
    private var starts: List<Long> = emptyList()
    /** Each segment's length on the Reel, its source length (ms), and level. */
    private var lengths: List<Long> = emptyList()
    private var sources: List<Long> = emptyList()
    private var volumes: List<Float> = emptyList()
    private var images: List<Boolean> = emptyList()
    private var tries = 0
    private var level = 1f

    init {
        player.setImageOutput(object : ImageOutput {
            override fun onImageAvailable(presentationTimeUs: Long, bitmap: Bitmap) { still = bitmap }
            override fun onDisabled() { still = null }
        })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = settle()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { tries = 0; problem = null }
                sound.playWhenReady = isPlaying
                if (isPlaying) syncSound(force = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Load again where it was; after three tries, say so on the video.
                if (tries < MAX_TRIES) {
                    tries++
                    val at = positionMs()
                    plan?.let { load(it, files, at) }
                } else {
                    problem = "Preview couldn't play: tap to try again"
                }
            }
        })
    }

    /** The current segment's speed, level and whether it's a still. */
    private fun settle() {
        val i = player.currentMediaItemIndex
        val speed = if (images.getOrElse(i) { false }) 1f else (sources.getOrElse(i) { 1L }.toFloat() / lengths.getOrElse(i) { 1L }.coerceAtLeast(1)).coerceIn(0.1f, 8f)
        player.playbackParameters = PlaybackParameters(speed)
        player.volume = volumes.getOrElse(i) { 1f } * level
        if (!images.getOrElse(i) { false }) still = null
    }

    /** Loads [p] and goes to [atMs] (Reel time). */
    fun load(p: StudioPlan, files: Map<String, Uri>, atMs: Long) {
        val items = ArrayList<MediaItem>()
        val lens = ArrayList<Long>()
        val srcs = ArrayList<Long>()
        val vols = ArrayList<Float>()
        val imgs = ArrayList<Boolean>()
        val first = p.clips.firstOrNull() ?: return
        val last = p.clips.last()
        for (seg in p.segments) {
            val cs = seg as? ClipSegment
            val (clip, from) = when (seg) {
                is ClipSegment -> seg to seg.inMs
                is TitleSegment -> first to (first.inMs - seg.durMs).coerceAtLeast(0)
                is StatsSegment -> last to 0L
            }
            val uri = files[clip.bit.momentId] ?: clip.bit.source?.let(Uri::parse) ?: return
            val dur = seg.durMs.coerceAtLeast(MIN_MS)
            val stillFile = cs?.still?.let(::File)?.takeIf { it.isFile }
            val reversed = cs?.reverse?.let(::File)?.takeIf { it.isFile }
            // How much of the clip plays in the segment (more than it shows when sped up).
            val src = when {
                stillFile != null -> dur
                cs != null -> cs.sourceMs.coerceAtMost(clip.bit.clipDurationMs - from).coerceAtLeast(MIN_MS)
                else -> dur.coerceAtMost(clip.bit.clipDurationMs - from).coerceAtLeast(MIN_MS)
            }
            items += when {
                stillFile != null -> MediaItem.Builder().setUri(Uri.fromFile(stillFile)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(dur).build()
                reversed != null -> clipped(Uri.fromFile(reversed), 0, src)
                else -> clipped(uri, from, from + src)
            }
            lens += dur
            srcs += src
            imgs += stillFile != null
            vols += if (cs != null && stillFile == null && reversed == null) ((if (cs.lines.isNotEmpty()) 1f else CLIP_RIDING) * cs.volume * p.mix.gain(TrackKind.CLIPS)).coerceIn(0f, 1f) else 0f
        }
        plan = p
        this.files = files
        starts = TimelineEdits.starts(p)
        lengths = lens
        sources = srcs
        volumes = vols
        images = imgs
        val (i, local) = TimelineEdits.at(p, atMs.coerceIn(0, p.totalMs))
        player.setMediaItems(items, i, sourceMs(i, local))
        player.prepare()
        settle()
        syncSound(force = true)
    }

    private fun clipped(uri: Uri, fromMs: Long, toMs: Long) = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).setEndPositionMs(toMs).build())
        .build()

    /** [local] Reel ms into segment [i] as ms into its item (its clip time runs at the segment's speed). */
    private fun sourceMs(i: Int, local: Long): Long = local * sources.getOrElse(i) { 1L } / lengths.getOrElse(i) { 1L }.coerceAtLeast(1)

    /** Where the preview is, in Reel ms. */
    fun positionMs(): Long {
        val i = player.currentMediaItemIndex
        val local = player.currentPosition * lengths.getOrElse(i) { 1L } / sources.getOrElse(i) { 1L }.coerceAtLeast(1)
        return (starts.getOrNull(i) ?: 0L) + local
    }

    fun seek(ms: Long) {
        val p = plan ?: return
        val (i, local) = TimelineEdits.at(p, ms.coerceIn(0, p.totalMs))
        player.seekTo(i, sourceMs(i, local))
        syncSound(force = true)
    }

    /** Plays the mixed sound tracks from [file] (null: none), in step with the picture. */
    fun setSound(file: File?) {
        if (file == null) { sound.clearMediaItems(); return }
        sound.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        sound.prepare()
        syncSound(force = true)
        sound.playWhenReady = player.isPlaying
    }

    /** Keeps the sound tracks with the picture (call often while playing); [force] jumps it there now. */
    fun syncSound(force: Boolean = false) {
        if (sound.mediaItemCount == 0) return
        val at = positionMs()
        if (force || abs(sound.currentPosition - at) > DRIFT_MS) sound.seekTo(at)
    }

    /** All sound at [v] of its level (quiet while the rider records a voice-over). */
    fun setLevel(v: Float) {
        level = v
        player.volume = volumes.getOrElse(player.currentMediaItemIndex) { 1f } * v
        sound.volume = v
    }

    /** After "tap to try again". */
    fun retry() {
        tries = 0
        problem = null
        val at = positionMs()
        plan?.let { load(it, files, at) }
    }

    fun release() {
        player.release()
        sound.release()
    }

    private companion object {
        const val PRELOAD_US = 2_000_000L
        const val MAX_TRIES = 3
        const val DRIFT_MS = 120L
        const val MIN_MS = 100L
        /** Riding noise plays a little quieter than talking (as in the made video). */
        const val CLIP_RIDING = 0.55f
    }
}
