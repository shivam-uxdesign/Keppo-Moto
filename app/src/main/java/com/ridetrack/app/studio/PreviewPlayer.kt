package com.ridetrack.app.studio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays an edit straight from the clips, without making the video: each segment is its clip's
 * part, one after another. Graphics are drawn on top by the editor; the vibe's colour and camera
 * moves only appear in the made video.
 */
class PreviewPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ALL }
    private var plan: StudioPlan? = null
    private var starts: List<Long> = emptyList()
    private var volumes: List<Float> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                player.volume = volumes.getOrElse(player.currentMediaItemIndex) { 1f }
            }
        })
    }

    /** Loads [p] and goes back to [atMs] (Reel time). */
    fun load(p: StudioPlan, files: Map<String, Uri>, atMs: Long) {
        val items = ArrayList<MediaItem>()
        val vols = ArrayList<Float>()
        val first = p.clips.firstOrNull() ?: return
        val last = p.clips.last()
        p.segments.forEach { seg ->
            val (clip, from) = when (seg) {
                is ClipSegment -> seg to seg.inMs
                is TitleSegment -> first to (first.inMs - seg.durMs).coerceAtLeast(0)
                is StatsSegment -> last to 0L
            }
            val uri = files[clip.bit.momentId] ?: clip.bit.source?.let(Uri::parse) ?: return@forEach
            // The simple preview plays every clip at normal speed: as long as the segment.
            val dur = seg.durMs.coerceAtMost(clip.bit.clipDurationMs - from).coerceAtLeast(100)
            items += MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(from + dur).build())
                .build()
            vols += if (seg is ClipSegment) ((if (seg.lines.isNotEmpty()) 1f else 0.55f) * seg.volume).coerceIn(0f, 1f) else 0f
        }
        if (items.size != p.segments.size) return
        plan = p
        starts = TimelineEdits.starts(p)
        volumes = vols
        val (i, local) = TimelineEdits.at(p, atMs.coerceIn(0, p.totalMs))
        player.setMediaItems(items, i, local)
        player.volume = vols.getOrElse(i) { 1f }
        player.prepare()
    }

    /** Where the preview is, in Reel ms. */
    fun positionMs(): Long = (starts.getOrNull(player.currentMediaItemIndex) ?: 0L) + player.currentPosition

    fun seek(ms: Long) {
        val p = plan ?: return
        val (i, local) = TimelineEdits.at(p, ms.coerceIn(0, p.totalMs))
        player.seekTo(i, local)
    }

    fun release() = player.release()
}

/**
 * Plays the edit exactly as the made video will look and sound (graphics, captions, colour,
 * camera moves, the mix), with Media3's composition player. Layers show as stills on top. If the
 * phone can't play it, [onFailed] gets the error and the editor goes back to the simple preview.
 */
// CompositionPlayer is marked for Media3's own use in 1.5; it's the only exact preview there is.
@android.annotation.SuppressLint("RestrictedApi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExactPreview(private val context: Context, private val onFailed: (Throwable) -> Unit) {
    val player = androidx.media3.transformer.CompositionPlayer.Builder(context).build()
    private var total = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                works = false
                onFailed(error)
            }
        })
    }

    /** Loads [input]'s edit and goes to [atMs]. */
    fun load(input: RenderInput, atMs: Long) {
        try {
            val composition = StudioRenderer(context).previewComposition(input)
            total = input.plan.totalMs
            player.setComposition(composition)
            player.prepare()
            player.seekTo(atMs.coerceIn(0, total))
        } catch (e: Exception) {
            works = false
            onFailed(e)
        }
    }

    fun positionMs(): Long = player.currentPosition.coerceIn(0, total)

    fun seek(ms: Long) = player.seekTo(ms.coerceIn(0, total))

    fun release() = player.release()

    companion object {
        /** False once it failed on this phone (this session): the simple preview is used. */
        @Volatile var works = true
    }
}
