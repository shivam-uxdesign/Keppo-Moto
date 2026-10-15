package com.ridetrack.app.studio

/** How a video is written out: size, frame rate and bitrate. */
data class OutputSpec(val width: Int = 1080, val height: Int = 1920, val fps: Int = 30, val bitrate: Int = 10_000_000) {
    val label: String get() = "${if (height >= 3840) "4K" else "${width}p"} · $fps fps"
}

/** Where a video is going, with the settings that suit it. */
enum class ExportPreset(val label: String, val blurb: String, val spec: OutputSpec, val splitSec: Int? = null) {
    INSTAGRAM("Instagram Reel", "1080p, 30 fps, high quality", OutputSpec(1080, 1920, 30, 10_000_000)),
    SHORTS("YouTube Short", "1080p, 30 fps, highest quality", OutputSpec(1080, 1920, 30, 14_000_000)),
    STORY("Story", "1080p, split into 60 s parts", OutputSpec(1080, 1920, 30, 8_000_000), splitSec = 60),
    WHATSAPP("WhatsApp", "720p, a small file", OutputSpec(720, 1280, 30, 3_000_000)),
}

/**
 * The quality picked in the editor's top bar: [height] 1280 (720p), 1920 (1080p) or 3840 (4K),
 * 60 fps or 30, and [level] 0 smaller, 1 recommended, 2 best (the bitrate).
 */
data class Quality(val height: Int = 1920, val fps60: Boolean = false, val level: Int = 1) {
    val label: String get() = when { height >= 3840 -> "4K"; height <= 1280 -> "720p"; else -> "1080p" } + if (fps60) " 60" else ""
}

/** Export maths. Pure, unit-tested. */
object Exports {
    /** [base] at [q]'s size, frame rate and quality (bitrate scaled with the picture). */
    fun withQuality(base: OutputSpec, q: Quality): OutputSpec {
        val h = q.height
        val w = h * 9 / 16
        val pixels = (w.toDouble() * h) / (base.width.toDouble() * base.height)
        val levelK = when (q.level) { 0 -> 0.6; 2 -> 1.6; else -> 1.0 }
        val fpsK = if (q.fps60) 1.5 else 1.0
        return base.copy(width = w, height = h, fps = if (q.fps60) 60 else 30, bitrate = (base.bitrate * pixels.coerceAtMost(3.0).coerceAtLeast(0.45) * levelK * fpsK).toInt())
    }

    /** About how big the file will be, in bytes (video and sound). */
    fun sizeBytes(spec: OutputSpec, durationMs: Long): Long = (spec.bitrate.toLong() + 128_000L) * durationMs / 8_000L

    /** "≈ 38 MB". */
    fun sizeLabel(bytes: Long): String = if (bytes < 1_000_000) "≈ ${(bytes / 1000).coerceAtLeast(1)} KB" else "≈ ${(bytes + 500_000) / 1_000_000} MB"

    /** Where a Story is cut: parts of at most [partMs], the last never shorter than 3 s (it joins the one before). */
    fun parts(totalMs: Long, partMs: Long): List<LongRange> {
        if (totalMs <= partMs) return listOf(0L..totalMs)
        val out = ArrayList<LongRange>()
        var t = 0L
        while (t < totalMs) {
            val end = minOf(t + partMs, totalMs)
            out += t..end
            t = end
        }
        val last = out.last()
        if (out.size > 1 && last.last - last.first < 3_000) {
            out.removeAt(out.lastIndex)
            val prev = out.removeAt(out.lastIndex)
            // Too long for one part: split the two evenly instead.
            val mid = (prev.first + last.last) / 2
            out += prev.first..mid
            out += mid..last.last
        }
        return out
    }

    /** 4K only when every clip is 4K; 60 fps only when every clip was filmed at 50 fps or more. */
    fun choices(sources: List<Triple<Int, Int, Float>>): Pair<Boolean, Boolean> {
        if (sources.isEmpty()) return false to false
        val k4 = sources.all { minOf(it.first, it.second) >= 2160 }
        val fps60 = sources.all { it.third >= 50f }
        return k4 to fps60
    }
}
