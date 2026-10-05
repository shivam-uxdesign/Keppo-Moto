package com.ridetrack.app.share

import com.ridetrack.telemetry.model.TelemetrySample

/** A moment the 3D video can stop at. Times are the ride's clock (ms). */
data class VideoMoment(
    val id: String,
    val timeMillis: Long,
    /** A video clip (false = a photo, shown for a second). */
    val isClip: Boolean,
    /** When the clip's first frame was filmed, and its length. */
    val videoStartMillis: Long = timeMillis,
    val durationMillis: Long = 0,
    val label: String = "",
)

/** One stretch of the finished video. */
sealed interface VideoPart {
    /** How long it lasts in the video (ms). */
    val outMillis: Long

    /** The map, moving through the ride from [fromRide] to [toRide]. */
    data class Map(val fromRide: Long, val toRide: Long, override val outMillis: Long) : VideoPart

    /**
     * The map paused at [atRide] while a moment plays in the centre card: for a clip, the part
     * from [clipFrom] to [clipTo] (ms into its file) at real speed; for a photo, the photo.
     */
    data class Hold(val moment: VideoMoment, val atRide: Long, val clipFrom: Long, val clipTo: Long, override val outMillis: Long) : VideoPart
}

/**
 * The 3D ride video, planned before anything is rendered: the trimmed part of the ride played
 * at [speed]×, stopping at each included moment. Pure, so it is unit-tested.
 */
data class RideVideoPlan(val parts: List<VideoPart>) {
    val totalMillis: Long get() = parts.sumOf { it.outMillis }
    val mapMillis: Long get() = parts.filterIsInstance<VideoPart.Map>().sumOf { it.outMillis }
    val clipCount: Int get() = parts.count { it is VideoPart.Hold && it.moment.isClip }

    /** What's on screen at [outMillis] into the video: the part and how far into it. */
    fun at(outMillis: Long): Pair<VideoPart, Long>? {
        var t = outMillis
        for (p in parts) {
            if (t < p.outMillis) return p to t
            t -= p.outMillis
        }
        return parts.lastOrNull()?.let { it to it.outMillis }
    }

    /** The ride time shown at [outMillis] into the video (a hold shows its moment's time). */
    fun rideTimeAt(outMillis: Long): Long? {
        val (p, local) = at(outMillis) ?: return null
        return when (p) {
            is VideoPart.Map -> p.fromRide + if (p.outMillis == 0L) 0 else (p.toRide - p.fromRide) * local / p.outMillis
            is VideoPart.Hold -> if (p.moment.isClip) p.moment.videoStartMillis + p.clipFrom + local else p.atRide
        }
    }

    companion object {
        const val PHOTO_MILLIS = 1_000L

        /**
         * [clipSeconds]: 0 = no stops, else how much of each clip to play, centred on its event;
         * [FULL_CLIP] = the whole clip. [moments] are the ones the rider kept; those outside the
         * trim are left out.
         */
        fun of(fromRide: Long, toRide: Long, speed: Int, clipSeconds: Int, moments: List<VideoMoment>): RideVideoPlan {
            require(toRide > fromRide && speed > 0)
            val stops = if (clipSeconds == 0) emptyList() else moments.filter { it.timeMillis in fromRide..toRide }.sortedBy { it.timeMillis }
            val parts = ArrayList<VideoPart>()
            var at = fromRide
            fun mapTo(t: Long) {
                if (t > at) parts += VideoPart.Map(at, t, (t - at) / speed)
                at = t
            }
            for (m in stops) {
                mapTo(m.timeMillis)
                parts += if (m.isClip) {
                    val (from, to) = clipWindow(m, clipSeconds)
                    VideoPart.Hold(m, m.timeMillis, from, to, to - from)
                } else {
                    VideoPart.Hold(m, m.timeMillis, 0, 0, PHOTO_MILLIS)
                }
            }
            mapTo(toRide)
            return RideVideoPlan(parts.filter { it.outMillis > 0 })
        }

        const val FULL_CLIP = 20

        /** The part of a clip to play: [seconds] centred on its event, kept inside the file. */
        fun clipWindow(m: VideoMoment, seconds: Int): Pair<Long, Long> {
            val dur = m.durationMillis.coerceAtLeast(0)
            if (seconds >= FULL_CLIP || dur <= seconds * 1000L) return 0L to dur
            val len = seconds * 1000L
            val event = (m.timeMillis - m.videoStartMillis).coerceIn(0, dur)
            val from = (event - len / 2).coerceIn(0, dur - len)
            return from to from + len
        }

        /** The fastest stretch of [spanMillis] (by average speed), as ride times; null if the ride is shorter. */
        fun bestStretch(samples: List<TelemetrySample>, spanMillis: Long): LongRange? {
            if (samples.size < 2) return null
            val start = samples.first().timeMillis
            val end = samples.last().timeMillis
            if (end - start <= spanMillis) return start..end
            var best = -1.0
            var bestFrom = start
            var j = 0
            var sum = 0.0
            var n = 0
            for (i in samples.indices) {
                val from = samples[i].timeMillis
                if (from + spanMillis > end) break
                while (j < samples.size && samples[j].timeMillis < from + spanMillis) {
                    sum += samples[j].speedMps ?: 0.0
                    n++
                    j++
                }
                if (n > 0 && sum / n > best) {
                    best = sum / n
                    bestFrom = from
                }
                sum -= samples[i].speedMps ?: 0.0
                n--
            }
            return bestFrom..bestFrom + spanMillis
        }

        /** The stretch of [spanMillis] holding the most clips, padded a little; null without clips. */
        fun aroundMoments(moments: List<VideoMoment>, spanMillis: Long, rideStart: Long, rideEnd: Long): LongRange? {
            val clips = moments.filter { it.isClip }.sortedBy { it.timeMillis }
            if (clips.isEmpty()) return null
            var bestN = 0
            var bestAt = clips.first().timeMillis
            for (c in clips) {
                val n = clips.count { it.timeMillis in c.timeMillis..c.timeMillis + spanMillis }
                if (n > bestN) {
                    bestN = n
                    bestAt = c.timeMillis
                }
            }
            val from = (bestAt - PAD_MILLIS).coerceAtLeast(rideStart)
            return from..(from + spanMillis + 2 * PAD_MILLIS).coerceAtMost(rideEnd)
        }

        private const val PAD_MILLIS = 30_000L
    }
}
