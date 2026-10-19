package com.ridetrack.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The app's main colour taken from the rider's bike. Any colour can be picked, so it's checked
 * against the theme's background and, when text on or around it wouldn't be readable, a lighter
 * (dark theme) or darker (light theme) shade of the same colour is used instead. Pure, on ARGB ints.
 */
object Accent {
    /** Keppo teal: the app's own colour, which keeps the designed palettes untouched. */
    const val TEAL = 0xFF69C8CB.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF0A0A0B.toInt()
    /** Accent text and controls on the background: WCAG AA for text. */
    const val TARGET = 4.5

    fun luminance(argb: Int): Double {
        fun ch(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [argb] as hue (0–360), saturation and lightness (0–1). */
    fun toHsl(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** [argb] or the nearest shade of it that reads at [target] against [background]. */
    fun readable(argb: Int, background: Int, target: Double = TARGET): Int {
        if (contrast(argb, background) >= target) return argb or (0xFF shl 24)
        val (h, s, l) = toHsl(argb).let { Triple(it[0], it[1], it[2]) }
        val lighter = luminance(background) < 0.5
        var light = l
        repeat(100) {
            light = if (lighter) min(1f, light + 0.01f) else max(0f, light - 0.01f)
            val c = fromHsl(h, s, light)
            if (contrast(c, background) >= target) return c
        }
        return if (lighter) WHITE else INK
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Text on the accent: whichever of white and ink reads better. */
    fun onAccent(argb: Int): Int = if (contrast(argb, WHITE) >= contrast(argb, INK)) WHITE else INK

    fun hex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** "#E4572E", "e4572e", "#e57" → ARGB; null if it isn't a colour. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF shl 24) or t.toInt(16)
    }
}

/** This palette with its main colour taken from [accent]; teal or null keeps it as designed. */
fun RtPalette.withAccent(accent: Int?): RtPalette {
    if (accent == null || (accent or (0xFF shl 24)) == Accent.TEAL) return this
    val bg = background.toArgb()
    val primary = Accent.readable(accent, bg)
    val container = if (isDark) Accent.mix(bg, primary, 0.25f) else Accent.mix(0xFFFFFFFF.toInt(), primary, 0.22f)
    return copy(
        primary = Color(primary),
        onPrimary = Color(Accent.onAccent(primary)),
        primaryContainer = Color(container),
        onPrimaryContainer = if (isDark) Color(primary) else Color(Accent.readable(primary, container, 7.0)),
    )
}
