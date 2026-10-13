package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.ridetrack.app.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the title and stats show about the ride. Route points are -1..1 (north up), with speed 0..1. */
data class RideCard(
    val title: String,
    val subtitle: String,
    val route: List<Triple<Float, Float, Float>>,
    val stats: List<Pair<String, String>>,
)

/** Where a frame is: the segment, ms into it, and its neighbours (for the transition over each cut). */
data class FrameAt(
    val vibe: Vibe,
    val localMs: Long,
    val durMs: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    /** The transitions on the cut into this segment and out of it, and how long each takes. */
    val inKind: TransitionKind = TransitionKind.STYLE,
    val inMs: Long = vibe.transitionMs,
    val outKind: TransitionKind = TransitionKind.STYLE,
    val outMs: Long = vibe.transitionMs,
    /** The segment's place in the Reel (the cut into it is cut [cut]): Film's light leak and gate jump alternate with it. */
    val cut: Int = 0,
    /** The song's beat when the rider set a tempo; Bold's kick on the beat only runs with one. */
    val beatMs: Long? = null,
)

/** A camera move on the footage: zoom [k] about the centre, then a shift of [x], [y] px, a tilt of [deg]. */
data class Cam(val k: Float, val x: Float, val y: Float, val deg: Float)

/**
 * Keppo Studio's graphics, drawn per frame on a transparent 1080×1920 overlay: captions,
 * speed, the title and stats, and each vibe's transition over the cut. Nothing here mixes
 * two clips; each transition is a camera move plus graphics that cover the cut. Every size,
 * colour, radius, duration and easing comes from [Tok]; each vibe draws in its own language
 * ([Vibe.spec]). Sizes are in s units (a 540-wide frame) and scaled.
 */
class StudioArt(
    context: Context,
    val w: Int = 1080,
    val h: Int = 1920,
    private val brand: BrandKit? = null,
    /** Grain and flicker on or off; null = the vibe's own (Film only). */
    private val grainOn: Boolean? = null,
    /** The rider's colour for the style; null = the style's own. */
    private val accentOverride: Int? = null,
) {
    private val s = w / 540f
    private val W = w.toFloat()
    private val H = h.toFloat()

    private val anton = font(context, R.font.anton)
    private val archivo = font(context, R.font.archivo_black)
    private val orbitron = font(context, R.font.orbitron_black)
    private val serifItalic = font(context, R.font.instrument_serif_italic)
    private val serif = font(context, R.font.instrument_serif)
    private val fredoka = font(context, R.font.fredoka_semibold)
    private val mono = font(context, R.font.ibm_plex_mono)
    private val monoMed = font(context, R.font.ibm_plex_mono_medium)
    private val dmSerif = font(context, R.font.dm_serif_display)
    private val marker = font(context, R.font.permanent_marker)
    private val geist = font(context, R.font.geist_semibold)
    private val geistMed = font(context, R.font.geist_medium)
    private val sys: Typeface = Typeface.DEFAULT_BOLD
    private val sysReg: Typeface = Typeface.DEFAULT

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The brand kit's logo and font, when the style uses it. */
    private val logo: Bitmap? = brand?.logo?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
    private val brandFont: Typeface? = brand?.font?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }

    /** The typeface the rider picked; null = the look's own. */
    fun faceOf(f: FontChoice): Typeface? = when (f) {
        FontChoice.STYLE -> null
        FontChoice.BOLD -> anton
        FontChoice.SERIF -> serif
        FontChoice.CLEAN -> geist
        FontChoice.HAND -> marker
        FontChoice.BRAND -> brandFont ?: geist
    }

    /** Plain text and captions in the brand's font when there is one. */
    private val plainFace: Typeface get() = brandFont ?: geist

    private fun font(c: Context, id: Int) = runCatching { ResourcesCompat.getFont(c, id) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    // ---- each vibe's language ----------------------------------------------------------------

    /** Titles, hooks, covers and section text. */
    private fun headFace(v: Vibe): Typeface = when (v) {
        Vibe.HYPE -> archivo
        Vibe.CINE -> serifItalic
        Vibe.CHILL -> fredoka
        Vibe.VLOG -> geist
        Vibe.FILM -> monoMed
        Vibe.EDITORIAL -> dmSerif
        Vibe.NATIVE -> sys
        Vibe.SPEEDTRAP -> orbitron
    }

    /** Numbers: the stats and the speed sticker (Cinematic's upright serif; italic is for titles). */
    private fun numFace(v: Vibe): Typeface = if (v == Vibe.CINE) serif else headFace(v)

    /** Small labels and units. */
    private fun labelFace(v: Vibe): Typeface = when (v) {
        Vibe.CHILL -> fredoka
        Vibe.FILM -> mono
        Vibe.NATIVE -> sysReg
        Vibe.SPEEDTRAP -> orbitron
        else -> geistMed
    }

    private fun accent(v: Vibe): Int = accentOverride ?: v.spec.accent ?: Tok.WHITE

    /** The style's colour, or null for Native's none (unless the rider picked one). */
    private fun accentOrNull(v: Vibe): Int? = accentOverride ?: v.spec.accent

    /** Hooks and section text; Film and Native stay at caption size. */
    private fun hookSize(v: Vibe) = when (v) { Vibe.FILM, Vibe.NATIVE -> Tok.CAP; Vibe.HYPE -> Tok.HERO; else -> Tok.CAP_L }

    private fun titleSize(v: Vibe) = when (v) { Vibe.FILM, Vibe.NATIVE -> Tok.CAP; Vibe.HYPE -> Tok.HERO; else -> Tok.TITLE }

    /** Editorial's graphics move at 12 frames a second (stop-motion); the others are smooth. */
    private fun tq(v: Vibe, ms: Float): Float = if (v == Vibe.EDITORIAL) floor(ms / Tok.STOP_MOTION_MS) * Tok.STOP_MOTION_MS else ms

    // ---- timing ------------------------------------------------------------------------------

    /** 0..1 across the cut (0.5 = the cut) when this frame is inside a transition, else null. */
    fun edge(f: FrameAt): Float? {
        val t = f.localMs.toFloat()
        if (f.hasPrev && f.inKind.on(f.vibe) != TransitionKind.CUT) {
            val d = f.inMs / 2f
            if (t < d) return 0.5f + t / (2 * d)
        }
        if (f.hasNext && f.outKind.on(f.vibe) != TransitionKind.CUT) {
            val d = f.outMs / 2f
            if (t > f.durMs - d) return (t - (f.durMs - d)) / (2 * d)
        }
        return null
    }

    /** The camera on a clip: the vibe's slow push (Film: gate weave), Bold's kick on a set tempo, and each cut's own move. */
    fun camera(f: FrameAt): Cam {
        val t = f.localMs.toFloat()
        var k = f.vibe.spec.push?.let { (a, b) -> a + (b - a) * Tok.prog(t, f.durMs) } ?: 1f
        var x = 0f
        var y = 0f
        var r = 0f
        if (f.vibe == Vibe.HYPE) f.beatMs?.let { b -> k += KICK * max(0f, 1 - (t % b) / b * KICK_SPAN) }
        if (f.vibe == Vibe.FILM) {
            x += W * WEAVE * noise(t / WEAVE_MS, 1)
            y += H * WEAVE * noise(t / WEAVE_MS, 2)
            // The gate jumps for two frames after each cut.
            if (f.cut > 0 && t < 2 * FRAME_MS) y += H * GATE_JUMP * (if (f.cut % 2 == 0) 1 else -1)
        }
        val dOut = f.outMs / 2f
        val dIn = f.inMs / 2f
        val outKind = f.outKind.on(f.vibe)
        val inKind = f.inKind.on(f.vibe)
        // o: 0 → 1 up to the cut out; i: 1 at the cut in → 0.
        val qo = if (f.hasNext && outKind != TransitionKind.CUT) cl((t - (f.durMs - dOut)) / dOut) else 0f
        val qi = if (f.hasPrev && inKind != TransitionKind.CUT) 1 - cl(t / dIn) else 0f
        for ((kind, o, i) in listOf(Triple(outKind, qo, 0f), Triple(inKind, 0f, qi))) {
            if (o == 0f && i == 0f) continue
            // Leaving speeds up into the cut (IN); landing settles (OUT).
            val leave = Tok.inn(o)
            val land = 1 - Tok.out(1 - i)
            when (kind) {
                TransitionKind.ZOOM -> k += ZOOM_K * (leave + land)
                TransitionKind.WHIP -> x += W * WHIP_X * (land - leave)
                TransitionKind.GLITCH -> x += W * GLITCH_X * (if (((t / Tok.T_MICRO * 3).toInt() and 1) == 0) 1 else -1) * max(o, i)
                TransitionKind.SLASH -> k += SLASH_K * (leave + land)
                TransitionKind.SHUTTER, TransitionKind.DIP, TransitionKind.SUN -> k += SOFT_K * Tok.inOut(max(o, i))
                TransitionKind.CARD -> x += W * PAPER_X * (Tok.inOut(i) - Tok.inOut(o))
                TransitionKind.ZOOM_THROUGH -> k += THROUGH_K * (leave + land)
                TransitionKind.TACH -> k += SOFT_K * Tok.inOut(max(o, i))
                // A gear change: the bike squats into the cut and kicks out of it, with a short shake.
                TransitionKind.GEAR -> {
                    k += GEAR_K * (leave + land)
                    y += H * GEAR_Y * (land - leave)
                    r += GEAR_DEG * sin(t / GEAR_SHAKE_MS * 2 * Math.PI.toFloat()) * max(o, i)
                }
                // Two quick revs into the cut (vroom vroom), then it lands zoomed and settles.
                TransitionKind.BLIP -> k += BLIP_K * (abs(sin(Math.PI.toFloat() * 2 * leave)) + land)
                TransitionKind.VISOR, TransitionKind.MIRROR, TransitionKind.EXHAUST, TransitionKind.BLUR -> k += SOFT_K * Tok.inOut(max(o, i))
                else -> {}
            }
        }
        return Cam(k, x, y, r)
    }

    /** The camera as Media3 wants it: a matrix in normalised device coordinates (-1..1, y up). */
    fun cameraMatrix(c: Cam): Matrix = Matrix().apply {
        val k = max(c.k, fill(c))
        // Rotate in pixel space so a tilt doesn't shear the 9:16 frame.
        setScale(W / 2, H / 2)
        postRotate(-c.deg)
        postScale(2 / W, 2 / H)
        postScale(k, k)
        postTranslate(2 * c.x / W, -2 * c.y / H)
    }

    /** The least zoom that keeps the frame filled after the camera's shift and tilt (no black edges). */
    fun fill(c: Cam): Float {
        val r = Math.toRadians(abs(c.deg).toDouble())
        return (kotlin.math.cos(r) + (H / W) * kotlin.math.sin(r)).toFloat() + 2 * abs(c.x) / W + 2 * abs(c.y) / H
    }

    // ---- a clip's overlay ---------------------------------------------------------------------

    /**
     * A clip's graphics. [lines] are the captions in ms from the segment start (the clip's words and
     * any voice-over); [opener] shows on the Reel's first clip: the title label and the hook line.
     */
    fun drawClip(c: Canvas, f: FrameAt, lines: List<CaptionLine>, kmh: Int, clock: String, captions: Boolean, opener: Opener? = null, look: CaptionLook = CaptionLook(), redline: Float = 0f, rev: Float? = null) {
        val t = tq(f.vibe, f.localMs.toFloat())
        vignette(c, f.vibe)
        grain(c, f.vibe, f.localMs.toFloat())
        if (f.vibe == Vibe.CINE) {
            p.reset(); p.color = Tok.INK
            c.drawRect(0f, 0f, W, H * LETTERBOX, p); c.drawRect(0f, H * (1 - LETTERBOX), W, H, p)
        }
        // The redline light sits on the video, under the text.
        if (f.vibe.spec.redline && redline > 0f) redline(c, redline, redlineStroke, accent(f.vibe))
        speedBadge(c, f.vibe, kmh, clock, rev)
        opener?.let { opening(c, f, it, t) }
        if (captions) caption(c, f.vibe, lines, t, look)
        edge(f)?.let { transition(c, f, it) }
    }

    /** What the first seconds say: a small title label (Film: the ride's date) and a hook line, readable with the sound off. */
    data class Opener(val label: String?, val hookLine: String?, val date: String? = null)

    /** The opening text in the title slot: gone by 2.8 s, or a moment before the hook clip ends. */
    private fun opening(c: Canvas, f: FrameAt, o: Opener, t: Float) {
        val end = min(Tok.OPENER_MS, f.durMs - Tok.T_BASE).toFloat()
        val ex = Tok.exit(Tok.T_BASE)
        val a = 1 - Tok.inn(Tok.prog(t - (end - ex), ex))
        if (t >= end || a <= 0f) return
        val above = (if (f.vibe == Vibe.FILM) o.date ?: o.label else o.label)?.takeIf { it.isNotBlank() }
        val line = o.hookLine?.takeIf { it.isNotBlank() }
        if (line == null) {
            above?.let { if (f.vibe == Vibe.HYPE) smallLabel(c, f.vibe, it, W * Tok.INFO_X, H * Tok.TITLE_Y, a * Tok.out(Tok.prog(t, Tok.T_BASE)), Paint.Align.LEFT) else smallLabel(c, f.vibe, it, W / 2, H * Tok.TITLE_Y, a * Tok.out(Tok.prog(t, Tok.T_BASE))) }
            return
        }
        headline(c, f.vibe, line, W / 2, H * Tok.TITLE_Y, hookSize(f.vibe), t, a, above = above)
    }

    private fun vignette(c: Canvas, v: Vibe) {
        val a = v.spec.vignette
        if (a <= 0f) return
        p.reset()
        p.shader = RadialGradient(W / 2, H / 2, H * VIGNETTE_R, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Tok.alpha(Color.BLACK, a)), floatArrayOf(0f, VIGNETTE_CLEAR, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
    }

    /** A tile of still noise, moved every frame: film grain. */
    private val noiseTile: Bitmap by lazy {
        val r = java.util.Random(NOISE_SEED)
        val px = IntArray(NOISE_PX * NOISE_PX) { val v = r.nextInt(256); Color.rgb(v, v, v) }
        Bitmap.createBitmap(px, NOISE_PX, NOISE_PX, Bitmap.Config.ARGB_8888)
    }

    /** Grain (Film; Cinematic when turned on) and Film's flicker. */
    private fun grain(c: Canvas, v: Vibe, ms: Float) {
        if (!(grainOn ?: v.spec.grain)) return
        val frame = (ms / FRAME_MS).toInt()
        val r = java.util.Random(frame.toLong())
        p.reset()
        p.shader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(r.nextFloat() * NOISE_PX * s, r.nextFloat() * NOISE_PX * s) })
        }
        p.alpha = ((if (v == Vibe.FILM) Tok.GRAIN_FILM else Tok.GRAIN_CINE) * 255).toInt()
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
        if (v == Vibe.FILM) veil(c, Tok.INK, Tok.FLICKER * (0.5f + 0.5f * noise(ms / FLICKER_MS, 3)))
    }

    /**
     * The redline light at [pulse] (0..1): a flat [color] over the whole frame, up to 30%; or, as
     * [stroke], a frame round the edge (14 px at 1080, 8 px soft inside), solid at the peak.
     */
    fun redline(c: Canvas, pulse: Float, stroke: Boolean, color: Int) {
        if (!stroke) return veil(c, color, REDLINE_FULL * pulse)
        val e = REDLINE_EDGE * s
        val soft = REDLINE_SOFT * s
        p.reset(); p.color = Tok.alpha(color, pulse)
        c.drawRect(0f, 0f, W, e, p); c.drawRect(0f, H - e, W, H, p)
        c.drawRect(0f, e, e, H - e, p); c.drawRect(W - e, e, W, H - e, p)
        val a = Tok.alpha(color, pulse)
        val z = Tok.alpha(color, 0f)
        fun band(x0: Float, y0: Float, x1: Float, y1: Float, gx0: Float, gy0: Float, gx1: Float, gy1: Float) {
            p.shader = LinearGradient(gx0, gy0, gx1, gy1, a, z, Shader.TileMode.CLAMP); c.drawRect(x0, y0, x1, y1, p)
        }
        band(e, e, W - e, e + soft, 0f, e, 0f, e + soft)
        band(e, H - e - soft, W - e, H - e, 0f, H - e, 0f, H - e - soft)
        band(e, e, e + soft, H - e, e, 0f, e + soft, 0f)
        band(W - e - soft, e, W - e, H - e, W - e, 0f, W - e - soft, 0f)
        p.shader = null
    }

    /** Speedtrap's redline light as a frame round the edge instead of the whole frame. */
    var redlineStroke: Boolean = false

    // ---- transitions -----------------------------------------------------------------------

    /** The graphic over the cut: [t] 0..1 across it (0.5 = the cut). No text on any of them. */
    fun transition(c: Canvas, f: FrameAt, t: Float) {
        val landing = t >= 0.5f
        val kind = (if (landing) f.inKind else f.outKind).on(f.vibe)
        val ms = if (landing) f.inMs else f.outMs
        val cov = 1 - abs(t - 0.5f) * 2
        when (kind) {
            TransitionKind.FADE -> veil(c, Tok.INK, Tok.inOut(cov))
            // A short warm overexposure, not pure white.
            TransitionKind.FLASH -> veil(c, Tok.PAPER, FLASH_A * Tok.inn(cov))
            TransitionKind.WHIP -> veil(c, Tok.INK, Tok.A_LOW * cov)
            TransitionKind.GLITCH -> glitch(c, t, cov)
            // Bold: the camera punches in, then one frame of yellow at the cut.
            TransitionKind.SLASH -> veil(c, accent(f.vibe), if (abs(t - 0.5f) * ms <= FRAME_MS) 1f else Tok.A_LOW * Tok.inn(cov))
            TransitionKind.SHUTTER -> shutter(c, cov)
            TransitionKind.SUN -> popWipe(c, cov, accent(f.vibe))
            TransitionKind.CARD -> paperSlide(c, t)
            // To black, held a moment at the cut.
            TransitionKind.DIP -> veil(c, Tok.INK, Tok.inOut(cl(cov / DIP_HOLD)))
            // A 120 ms dip to black at the cut; the camera does the rest.
            TransitionKind.ZOOM_THROUGH -> veil(c, Tok.INK, 1 - Tok.inn(cl(abs(t - 0.5f) * ms / (Tok.T_MICRO / 2f))))
            TransitionKind.LIGHT_LEAK -> lightLeak(c, cov, (if (landing) f.cut else f.cut + 1) % 2 == 0, accent(f.vibe))
            // Speedtrap: the frame darkens, the rev counter sweeps up to the redline and flushes red at the cut.
            TransitionKind.TACH -> {
                veil(c, Tok.INK, Tok.A_HIGH * Tok.inOut(cov))
                tach(c, Tok.inOut(cov), H * TACH_Y, W * TACH_R, accent(f.vibe), Tok.inOut(cov))
                veil(c, accent(f.vibe), Tok.A_LOW * Tok.inn(cov))
            }
            // A hard kick of light right at the shift; the camera does the jolt.
            TransitionKind.GEAR -> veil(c, Tok.PAPER, GEAR_FLASH * Tok.inn(cov) * Tok.inn(cov))
            TransitionKind.BLIP -> veil(c, Tok.INK, Tok.A_LOW * Tok.inn(cov))
            TransitionKind.VISOR -> visor(c, cov)
            TransitionKind.MIRROR -> mirror(c, cov)
            TransitionKind.EXHAUST -> exhaust(c, cov, if (landing) f.cut else f.cut + 1)
            // Split, Blur and Luma fade change the picture itself (PictureCuts); nothing on top.
            else -> {}
        }
    }

    /** A tinted helmet visor slides down over the clip (dark at the cut) and lifts off the next. */
    private fun visor(c: Canvas, cov: Float) {
        val e = Tok.inOut(cov)
        if (e <= 0f) return
        val edge = H * VISOR_REACH * e
        val dip = H * VISOR_CURVE
        val shape = android.graphics.Path().apply {
            moveTo(0f, -1f); lineTo(W, -1f); lineTo(W, edge - dip)
            quadTo(W / 2, edge + dip, 0f, edge - dip); close()
        }
        p.reset()
        p.isAntiAlias = true
        p.shader = LinearGradient(0f, 0f, 0f, edge.coerceAtLeast(1f), Tok.alpha(Tok.INK, VISOR_TOP_A), Tok.alpha(VISOR_TINT, VISOR_BOTTOM_A), Shader.TileMode.CLAMP)
        c.drawPath(shape, p)
        p.shader = null
        // The visor's rim, and a line of light along it.
        val rim = android.graphics.Path().apply { moveTo(W, edge - dip); quadTo(W / 2, edge + dip, 0f, edge - dip) }
        p.style = Paint.Style.STROKE
        p.strokeWidth = VISOR_RIM * s
        p.color = Tok.INK
        c.drawPath(rim, p)
        p.strokeWidth = Tok.RULE_S * s
        p.color = Tok.alpha(Tok.WHITE, VISOR_SHINE_A)
        c.save(); c.translate(0f, -VISOR_RIM * s); c.drawPath(rim, p); c.restore()
        // A soft reflection across the plastic.
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, edge * 0.2f, W, edge * 0.6f, intArrayOf(Tok.alpha(Tok.WHITE, 0f), Tok.alpha(Tok.WHITE, VISOR_GLARE_A), Tok.alpha(Tok.WHITE, 0f)), floatArrayOf(0.35f, 0.5f, 0.65f), Shader.TileMode.CLAMP)
        c.save(); c.clipPath(shape); c.drawRect(0f, 0f, W, H, p); c.restore()
        p.shader = null
    }

    /**
     * The view closes down to a rear-view mirror (dark round it, a chrome rim) by the cut; the next
     * clip opens out of the mirror to the full frame.
     */
    private fun mirror(c: Canvas, cov: Float) {
        val e = Tok.inOut(cov)
        if (e <= 0f) return
        val grow = 1 + (MIRROR_GROW - 1) * (1 - e)
        val mw = W * MIRROR_W * grow
        val mh = W * MIRROR_H * grow
        val cx = W / 2
        val cy = H * MIRROR_Y
        val rect = RectF(cx - mw / 2, cy - mh / 2, cx + mw / 2, cy + mh / 2)
        val around = android.graphics.Path().apply {
            fillType = android.graphics.Path.FillType.EVEN_ODD
            addRect(0f, 0f, W, H, android.graphics.Path.Direction.CW)
            addOval(rect, android.graphics.Path.Direction.CW)
        }
        val a = min(1f, e * MIRROR_DARK_IN)
        p.reset()
        p.isAntiAlias = true
        // The mirror's stem, down from its lower edge towards the bars.
        p.color = Tok.alpha(MIRROR_STEM, a)
        p.strokeWidth = MIRROR_STEM_W * s * grow
        p.strokeCap = Paint.Cap.ROUND
        c.drawPath(around, Paint(p).apply { style = Paint.Style.FILL; color = Tok.alpha(Tok.INK, a) })
        c.drawLine(cx + mw * 0.18f, rect.bottom, cx + mw * 0.32f, rect.bottom + mh * 0.9f, p)
        // The chrome rim.
        p.style = Paint.Style.STROKE
        p.strokeWidth = MIRROR_RIM * s * grow
        p.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, intArrayOf(Tok.alpha(Tok.WHITE, a), Tok.alpha(MIRROR_CHROME, a), Tok.alpha(Tok.WHITE, a)), null, Shader.TileMode.CLAMP)
        c.drawOval(rect, p)
        p.shader = null
    }

    /** Smoke puffs from the bottom corner fill the frame by the cut and clear off the next clip. */
    private fun exhaust(c: Canvas, cov: Float, cut: Int) {
        val e = Tok.inOut(cov)
        if (e <= 0f) return
        val rnd = java.util.Random(cut * 31L + 7)
        // A haze under the puffs, thick enough at the cut to hide it; the puffs keep it smoky.
        veil(c, SMOKE_DARK, SMOKE_HAZE * e * e * e)
        p.reset()
        p.isAntiAlias = true
        repeat(SMOKE_PUFFS) { n ->
            // Each puff starts low by the exhaust and drifts up and out as it grows.
            val sx = rnd.nextFloat()
            val sy = rnd.nextFloat()
            val size = SMOKE_MIN + SMOKE_SPREAD * rnd.nextFloat()
            val x = W * (SMOKE_FROM_X + (sx - SMOKE_FROM_X) * e)
            val y = H * (SMOKE_FROM_Y + (sy - SMOKE_FROM_Y) * e)
            val rad = (H * size * e).coerceAtLeast(1f)
            val tone = if (n % 2 == 0) SMOKE_LIGHT else SMOKE_DARK
            p.shader = RadialGradient(x, y, rad, intArrayOf(Tok.alpha(tone, SMOKE_A * e), Tok.alpha(tone, SMOKE_A * e * 0.6f), Tok.alpha(tone, 0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p)
        }
        p.shader = null
    }

    private fun veil(c: Canvas, color: Int, a: Float) {
        if (a <= 0f) return
        p.reset(); p.color = Tok.alpha(color, a)
        c.drawRect(0f, 0f, W, H, p)
    }

    /** Coloured bands that jump about for a moment across the cut (kept for saved edits; not offered). */
    private fun glitch(c: Canvas, t: Float, cov: Float) {
        if (cov <= GLITCH_MIN) return
        val rnd = java.util.Random((t * GLITCH_STEPS).toLong())
        p.reset()
        repeat(GLITCH_BANDS) {
            val y = rnd.nextFloat() * H
            val bh = (Tok.SP_S + rnd.nextFloat() * Tok.TITLE) * s
            val dx = (rnd.nextFloat() - 0.5f) * Tok.HERO * s
            p.color = Tok.alpha(if (it % 2 == 0) Tok.BLUE else Tok.RED, Tok.A_HIGH * cov)
            c.drawRect(dx, y, W + dx, y + bh, p)
        }
    }

    /** Cinematic: the bars close to black and open on the next clip. */
    private fun shutter(c: Canvas, cov: Float) {
        val bh = H / 2 * Tok.inOut(cov)
        if (bh <= 0f) return
        p.reset(); p.color = Tok.INK
        c.drawRect(0f, 0f, W, bh + 1, p); c.drawRect(0f, H - bh - 1, W, H, p)
    }

    /** Playful: a flat coral circle grows from the centre over the cut and shrinks off the next clip. */
    private fun popWipe(c: Canvas, cov: Float, color: Int) {
        val r = hypot(W, H) / 2 * Tok.inOut(cov)
        if (r < 1) return
        p.reset(); p.color = color
        c.drawCircle(W / 2, H / 2, r, p)
    }

    /** Editorial: a paper panel slides in from the right, then off to the left, an ink line on its leading edge. */
    private fun paperSlide(c: Canvas, t: Float) {
        val left = if (t < 0.5f) W * (1 - Tok.inOut(t * 2)) else -W * Tok.inOut((t - 0.5f) * 2)
        p.reset(); p.color = Tok.PAPER
        c.drawRect(left, 0f, left + W, H, p)
        p.color = Tok.INK
        c.drawRect(left - Tok.RULE_S * s, 0f, left, H, p)
    }

    /** Film: warm light (amber into paper) sweeps in from one edge, strongest at the cut. */
    private fun lightLeak(c: Canvas, cov: Float, fromLeft: Boolean, warm: Int) {
        val e = Tok.inOut(cov)
        if (e <= 0f) return
        val a = Tok.A_HIGH * e
        val reach = W * (LEAK_MIN + LEAK_REACH * e)
        val x0 = if (fromLeft) 0f else W
        val x1 = if (fromLeft) reach else W - reach
        p.reset()
        p.shader = LinearGradient(x0, 0f, x1, 0f, intArrayOf(Tok.alpha(warm, a), Tok.alpha(Tok.PAPER, a), Tok.alpha(Tok.PAPER, 0f)), floatArrayOf(0f, LEAK_MID, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
    }

    // ---- captions ----------------------------------------------------------------------------

    /** The line being said: its words, when each lands (highlight [Tok.LEAD] early), and until when it shows. */
    private class Spoken(val index: Int, val words: List<String>, val starts: FloatArray, val start: Float, val until: Float, val en: String?, val key: String?) {
        /** The word being said at [t] (the first until it starts). */
        fun active(t: Float): Int {
            var a = 0
            for (k in starts.indices) if (starts[k] <= t) a = k
            return a
        }
    }

    private fun spoken(lines: List<CaptionLine>, t: Float): Spoken? {
        val lead = Tok.LEAD.toFloat()
        for (k in lines.indices) {
            val l = lines[k]
            val from = l.startMs - lead
            val next = lines.getOrNull(k + 1)
            val until = min(l.endMs + Tok.LINE_HOLD_MS.toFloat(), next?.let { it.startMs - lead } ?: Float.MAX_VALUE)
            if (t < from || t >= until) continue
            val words = l.text.trim().split(WS).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            // Longer words take longer to say: each word's share of the line by its letters.
            val weights = words.map { w -> w.count { it.isLetterOrDigit() }.coerceAtLeast(1) }
            val total = weights.sum().toFloat()
            val span = (l.endMs - l.startMs).coerceAtLeast(1)
            var acc = 0
            val starts = FloatArray(words.size) { i -> (l.startMs + span * acc / total - lead).also { acc += weights[i] } }
            return Spoken(k, words, starts, from, until, l.en?.trim()?.takeIf { it.isNotEmpty() }, l.key)
        }
        return null
    }

    /** The caption being drawn: the rider's colours and typeface over the style's own, and whether words light up. */
    private class Cap(val vibe: Vibe, val ink: Int?, val hi: Int?, val bg: Int?, val face: Typeface?, val karaoke: Boolean)

    /** What's drawn of the caption block (for the band, the English line and the tap area), and its type size. */
    private val blk = RectF()
    private var capSize = Tok.CAP
    private var capLh = 0f
    /** Every colour drawn is multiplied by this (a line fading in or out). */
    private var fade = 1f

    /**
     * The captions at [t] ms in their style (the vibe's own, or the rider's pick), anchored in the
     * caption slot: the last row's centre at 0.66H, more rows growing upward. Returns the block's
     * bounds on the frame (for tapping), or null when no line shows.
     */
    fun caption(c: Canvas, vibe: Vibe, lines: List<CaptionLine>, t: Float, look: CaptionLook = CaptionLook()): RectF? {
        fade = 1f
        if (lines.isEmpty()) return null
        val sp = spoken(lines, t) ?: return null
        val kind = if (look.kind == CaptionKind.STYLE) vibe.spec.caption else look.kind
        val ay = (look.y ?: Tok.CAPTION_Y) * H
        val bg = look.background
        val paper = bg ?: when (kind) { CaptionKind.LABEL, CaptionKind.STRIP -> Tok.PAPER; CaptionKind.CHAT, CaptionKind.PLAIN -> Tok.WHITE; else -> null }
        // Each style's highlight: a colour (the rider's, else the vibe's accent where it reads), or the others dimmed.
        val accent = accentOrNull(vibe)
        val hi = look.highlight ?: when (kind) {
            // These dim the other words; a colour the rider picked lights the word being said instead.
            CaptionKind.CLEAN, CaptionKind.SERIF -> accentOverride
            CaptionKind.PUNCH -> accent ?: Tok.WHITE
            CaptionKind.LABEL -> accent?.takeIf { readable(it, paper!!) } ?: Tok.CORAL
            CaptionKind.STRIP -> accent?.takeIf { readable(it, paper!!) } ?: Tok.RED
            CaptionKind.CHAT -> accent?.takeIf { readable(it, paper!!) }
            CaptionKind.PLAIN -> if (look.box) accent?.takeIf { readable(it, paper!!) } else accent
            else -> accent
        }
        val k = Cap(vibe, look.color, hi, bg, faceOf(look.font), look.karaoke)
        blk.setEmpty()
        c.save()
        if (look.size != 1f) c.scale(look.size, look.size, W / 2, ay)
        // A plain line just swaps; the others fade out as the next comes.
        val lineOut = if (kind == CaptionKind.PLAIN) 1f else 1 - Tok.inn(1 - Tok.prog(sp.until - t, Tok.exit(Tok.T_BASE)))
        val band = look.box && kind != CaptionKind.PLAIN
        val pic = if (band) Picture() else null
        val dc = pic?.beginRecording(w, h) ?: c
        fade = lineOut
        when (kind) {
            CaptionKind.CLEAN -> cleanCaption(dc, sp, t, ay, k)
            CaptionKind.SERIF -> serifCaption(dc, sp, t, ay, k)
            CaptionKind.PUNCH -> punchCaption(dc, sp, t, ay, k)
            CaptionKind.LABEL -> labelCaption(dc, sp, t, ay, k, paper ?: Tok.PAPER)
            CaptionKind.CHAT -> chatCaption(dc, lines, sp, t, ay, k, paper ?: Tok.WHITE, lineOut)
            CaptionKind.TYPE -> typeCaption(dc, sp, t, ay, k)
            CaptionKind.STRIP -> stripCaption(dc, sp, t, ay, k, paper ?: Tok.PAPER)
            CaptionKind.DASH -> dashCaption(dc, sp, t, ay, k)
            else -> plainCaption(dc, sp, t, ay, k, look.box, paper ?: Tok.WHITE)
        }
        pic?.endRecording()
        val lineFade = fade
        // The English line belongs to the block: 0.6 × the caption size, half a line below it, inside the band.
        val en = sp.en?.takeIf { look.english && !blk.isEmpty }
        var enRows: List<IntRange> = emptyList()
        var enWords: List<String> = emptyList()
        var enLh = 0f
        val enTop = blk.bottom + capLh / 2
        if (en != null) {
            type(if (vibe == Vibe.NATIVE) sys else plainFace, enSize(), Tok.WHITE)
            enWords = en.split(WS).filter { it.isNotEmpty() }
            enRows = wrapIdx(enWords, enWords.indices, W * Tok.CAPTION_W).take(2)
            enLh = p.textSize * Tok.LEADING
        }
        val all = RectF(blk).apply { if (enRows.isNotEmpty()) union(W / 2 - 1, enTop, W / 2 + 1, enTop + enRows.size * enLh) }
        if (en != null) enRows.forEach { r -> all.union(W / 2 - p.measureText(enWords.slice(r).joinToString(" ")) / 2, enTop, W / 2 + p.measureText(enWords.slice(r).joinToString(" ")) / 2, enTop + enLh) }
        if (band && !blk.isEmpty) {
            val pad = Tok.SP_XL * s
            p.reset(); p.isAntiAlias = true; p.color = Tok.alpha(Tok.INK, Tok.A_MID * lineFade)
            c.drawRoundRect(RectF(all.left - pad, all.top - pad, all.right + pad, all.bottom + pad), Tok.R_MD * s, Tok.R_MD * s, p)
            all.inset(-pad, -pad)
        }
        pic?.let { c.drawPicture(it) }
        if (en != null) {
            type(if (vibe == Vibe.NATIVE) sys else plainFace, enSize(), Tok.WHITE, Paint.Align.CENTER)
            fade = lineFade
            soft()
            enRows.forEachIndexed { i, r ->
                ink(Tok.WHITE, Tok.QUIET)
                c.drawText(enWords.slice(r).joinToString(" "), W / 2, mid(enTop + enLh / 2 + i * enLh), p)
            }
        }
        c.restore()
        fade = 1f
        if (all.isEmpty) return null
        // Back to frame coordinates (the rider's size scales about the anchor).
        val z = look.size
        return RectF(W / 2 + (all.left - W / 2) * z, ay + (all.top - ay) * z, W / 2 + (all.right - W / 2) * z, ay + (all.bottom - ay) * z)
    }

    /** The English line: 0.6 × the caption size, never more than body size (next to poster-size captions it would compete). */
    private fun enSize() = min(Tok.snap(capSize * EN_SCALE), Tok.BODY)

    /** Clean: 2–3 words at a time, each chunk sliding up out of a mask; the word being said at full, the rest dimmed. */
    private fun cleanCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap) {
        val a = sp.active(t)
        val g = chunks(sp.words, 3).first { a in it }
        val face = k.face ?: geist
        val size = fit(face, sp.words.slice(g).joinToString(" "), Tok.CAP_L, W * Tok.CAPTION_W, Tok.CAPTION_FLOOR)
        type(face, size, Tok.WHITE, track = if (size >= Tok.CAP_L) Tok.TRACK_TIGHT else 0f)
        val rows = wrapIdx(sp.words, g, W * Tok.CAPTION_W)
        val lh = size * s * Tok.LEADING_TIGHT
        capSize = size; capLh = lh
        val q = Tok.out(Tok.prog(t - sp.starts[g.first], Tok.T_BASE))
        rows.forEachIndexed { ri, r ->
            val y = ay - (rows.size - 1 - ri) * lh
            c.save()
            if (q < 1f) c.clipRect(0f, y - lh / 2, W, y + lh / 2)
            soft()
            row(c, sp.words, r, W / 2, y + (1 - q) * Tok.SP_M * s, lh, mark = false) { i -> wordInk(k, i, a, k.ink ?: Tok.WHITE, Tok.DIM_CLEAN) }
            c.restore()
            mark(W / 2 - p.measureText(sp.words.slice(r).joinToString(" ")) / 2, y - lh / 2, W / 2 + p.measureText(sp.words.slice(r).joinToString(" ")) / 2, y + lh / 2)
        }
    }

    /** Cinematic: the whole line fades in with a small rise, sentence case, two rows at most. */
    private fun serifCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap) {
        val a = sp.active(t)
        type(k.face ?: serif, Tok.CAP, Tok.PAPER)
        val rows = window(wrapIdx(sp.words, sp.words.indices, W * Tok.CAPTION_W), a)
        val lh = Tok.CAP * s * Tok.LEADING
        capSize = Tok.CAP; capLh = lh
        val q = Tok.out(Tok.prog(t - sp.start, Tok.T_EMPH))
        fade *= q
        soft()
        rows.forEachIndexed { ri, r ->
            val y = ay - (rows.size - 1 - ri) * lh
            row(c, sp.words, r, W / 2, y + (1 - q) * Tok.RISE * s, lh) { i -> wordInk(k, i, a, k.ink ?: Tok.PAPER, Tok.DIM) }
        }
    }

    /** Bold: 2–3 words in capitals, each landing with a firm spring; the word being said on the accent block. */
    private fun punchCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap) {
        val a = sp.active(t)
        val words = sp.words.map { it.uppercase() }
        val g = chunks(words, 2).first { a in it }
        val face = k.face ?: archivo
        // As big as fits in two rows at most: two words usually stack, a poster's way.
        var size = words.slice(g).minOf { fit(face, it, Tok.HERO, W * Tok.CAPTION_W, Tok.CAPTION_FLOOR) }
        type(face, size, Tok.WHITE, track = POSTER_TRACK)
        var rows = wrapIdx(words, g, W * Tok.CAPTION_W)
        while (rows.size > 2 && size > Tok.CAPTION_FLOOR) {
            size = Tok.down(size)
            type(face, size, Tok.WHITE, track = POSTER_TRACK)
            rows = wrapIdx(words, g, W * Tok.CAPTION_W)
        }
        val lh = size * s * POSTER_LEADING
        capSize = size; capLh = lh
        val key = k.hi ?: Tok.YELLOW
        val space = p.measureText(" ")
        soft()
        rows.forEachIndexed { ri, r ->
            val y = ay - (rows.size - 1 - ri) * lh
            val total = p.measureText(words.slice(r).joinToString(" "))
            var x = W / 2 - total / 2
            mark(x, y - lh / 2, x + total, y + lh / 2)
            for (i in r) {
                val ww = p.measureText(words[i])
                val said = t >= sp.starts[i]
                // Each word snaps in, no bounce; the next one waits, quiet.
                val q = Tok.out(Tok.prog(t - sp.starts[i], Tok.T_MICRO))
                val sc = if (said) POSTER_FROM - (POSTER_FROM - 1) * q else 1f
                c.save(); c.translate(x + ww / 2, y); c.scale(sc, sc)
                // The key word turns yellow once it's said; before that it waits in white like the rest.
                ink(if (isKey(sp, i) && (said || !k.karaoke)) key else k.ink ?: Tok.WHITE, if (!k.karaoke || said) 1f else POSTER_WAIT)
                c.drawText(words[i], -ww / 2, mid(0f), p)
                c.restore()
                x += ww + space
            }
        }
        p.clearShadowLayer()
    }

    /**
     * Bold's headline, a poster: the first line huge and white, the rest a size smaller in yellow,
     * flush left (or centred on [cx] when [left] is off); each line snaps in.
     */
    private fun posterHeadline(c: Canvas, text: String, cx: Float, cy: Float, size: Float, ms: Float?, alpha: Float, above: String?, below: String?, maxW: Float, color: Int?, left: Boolean) {
        val t = ms ?: STILL
        val words = text.trim().split(WS).filter { it.isNotEmpty() }.map { it.uppercase() }
        if (words.isEmpty() || alpha <= 0f) return
        val (z, rows) = rowsAt(archivo, words, size, maxW, POSTER_TRACK)
        val lead = rows.first()
        val rest = if (rows.size > 1) (rows[1].first..words.lastIndex) else IntRange.EMPTY
        val zs = Tok.down(Tok.down(z))
        p.reset(); p.typeface = archivo; p.textSize = zs * s; p.letterSpacing = POSTER_TRACK
        val small = if (rest.isEmpty()) emptyList() else wrapIdx(words, rest, maxW).take(2)
        val lh0 = z * s * POSTER_LEADING
        val lhs = zs * s * POSTER_LEADING
        val blockH = lh0 + small.size * lhs
        val labelTop = cy - blockH / 2 - Tok.SP_L * s - Tok.LABEL * s
        val cyy = cy + if (above != null) max(0f, H * Tok.INFO_BOTTOM + Tok.SP_S * s - labelTop) else 0f
        val top = cyy - blockH / 2
        val x = if (left) W * Tok.INFO_X else cx
        val align = if (left) Paint.Align.LEFT else Paint.Align.CENTER
        val lines = listOf(Triple(lead, z, lh0)) + small.map { Triple(it, zs, lhs) }
        var y = top
        lines.forEachIndexed { ri, (r, zz, lh) ->
            val q = Tok.out(Tok.prog(t - ri * Tok.STAGGER, Tok.T_MICRO))
            y += lh
            if (q <= 0f) return@forEachIndexed
            type(archivo, zz, Tok.WHITE, align, POSTER_TRACK)
            fade = alpha * q
            soft()
            val sc = POSTER_FROM - (POSTER_FROM - 1) * q
            c.save(); c.scale(sc, sc, x, y - lh / 2)
            ink(color ?: if (ri == 0) Tok.WHITE else accent(Vibe.HYPE), 1f)
            c.drawText(words.slice(r).joinToString(" "), x, mid(y - lh / 2), p)
            c.restore()
        }
        fade = 1f
        val labelA = alpha * Tok.out(Tok.prog(t, Tok.T_BASE))
        above?.let { smallLabel(c, Vibe.HYPE, it, x, top - Tok.SP_L * s - Tok.LABEL * s / 2, labelA, align) }
        below?.let { smallLabel(c, Vibe.HYPE, it, x, top + blockH + Tok.SP_L * s + Tok.LABEL * s / 2, labelA, align) }
    }

    /** Playful: 3–4 words on a paper pill that pops in, tilted one way then the other; the word being said in the accent. */
    private fun labelCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap, paper: Int) {
        val a = sp.active(t)
        val groups = chunks(sp.words, 4)
        val gi = groups.indexOfFirst { a in it }
        val g = groups[gi]
        val face = k.face ?: fredoka
        val padX = Tok.SP_XL * s
        val padY = Tok.SP_M * s
        val size = fit(face, sp.words.slice(g).joinToString(" "), Tok.CAP, W * Tok.CAPTION_W - 2 * padX, Tok.CAPTION_FLOOR)
        type(face, size, inkOn(paper))
        val rows = wrapIdx(sp.words, g, W * Tok.CAPTION_W - 2 * padX)
        val lh = size * s * Tok.LEADING_TIGHT
        capSize = size; capLh = lh
        val bw = rows.maxOf { p.measureText(sp.words.slice(it).joinToString(" ")) } + 2 * padX
        val bh = rows.size * lh + 2 * padY
        val cy = ay - (rows.size - 1) * lh / 2
        val q = Tok.prog(t - sp.starts[g.first], Tok.T_BASE)
        val pop = POP_FROM + (1 - POP_FROM) * Tok.spring(q)
        val base = fade
        fade = base * Tok.out(q)
        c.save(); c.translate(W / 2, cy); c.rotate(if (gi % 2 == 0) -PLAYFUL_TILT else PLAYFUL_TILT); c.scale(pop, pop)
        p.setShadowLayer(Tok.SOFT_BLUR * s, 0f, Tok.SOFT_Y * s, Tok.alpha(Color.BLACK, Tok.A_LOW * fade))
        ink(paper, 1f)
        c.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), min(Tok.R_PILL * s, bh / 2), min(Tok.R_PILL * s, bh / 2), p)
        p.clearShadowLayer()
        rows.forEachIndexed { ri, r ->
            row(c, sp.words, r, 0f, -bh / 2 + padY + lh / 2 + ri * lh, lh, mark = false) { i -> wordInk(k, i, a, k.ink ?: inkOn(paper), Tok.DIM) }
        }
        c.restore()
        fade = base
        mark(W / 2 - bw / 2, cy - bh / 2, W / 2 + bw / 2, cy + bh / 2)
    }

    /** Chat: each line a bubble that fades up from the left; the one before stays, quieter (two at most). */
    private fun chatCaption(c: Canvas, lines: List<CaptionLine>, sp: Spoken, t: Float, ay: Float, k: Cap, bubble: Int, lineOut: Float) {
        val a = sp.active(t)
        val face = k.face ?: geist
        val base = k.ink ?: inkOn(bubble)
        val padX = Tok.SP_XL * s
        val padY = Tok.SP_M * s
        type(face, Tok.BODY, base)
        val lh = Tok.BODY * s * Tok.LEADING
        capSize = Tok.BODY; capLh = lh
        val prev = lines.getOrNull(sp.index - 1)?.takeIf { t - it.endMs < Tok.SECTION_TEXT_MS }
        var bottom = ay + lh / 2 + padY
        for (j in listOfNotNull(sp.index, prev?.let { sp.index - 1 })) {
            val l = lines[j]
            val now = j == sp.index
            val words = if (now) sp.words else l.text.trim().split(WS).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val rows = wrapIdx(words, words.indices, W * Tok.CAPTION_W - 2 * padX)
            val bw = rows.maxOf { p.measureText(words.slice(it).joinToString(" ")) } + 2 * padX
            val bh = rows.size * lh + 2 * padY
            val x = W * Tok.INFO_X
            val e = Tok.out(Tok.prog(t - (l.startMs - Tok.LEAD), Tok.T_BASE))
            fade = (if (now) lineOut else Tok.A_HIGH) * e
            val top = bottom - bh + (1 - e) * Tok.SP_S * s
            soft()
            ink(bubble, 1f)
            c.drawRoundRect(RectF(x, top, x + bw, top + bh), Tok.R_MD * s, Tok.R_MD * s, p)
            p.clearShadowLayer()
            mark(x, top, x + bw, top + bh)
            rows.forEachIndexed { ri, r ->
                row(c, words, r, x + padX, top + padY + lh / 2 + ri * lh, lh, Paint.Align.LEFT, mark = false) { i -> if (now) wordInk(k, i, a, base, Tok.DIM) else ink(base, 1f) }
            }
            bottom = top - Tok.SP_S * s
        }
        fade = lineOut
    }

    /** Plain (Native's own): white words, two rows at most, swapped with no animation; with "box", ink on a white box per row. */
    private fun plainCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap, box: Boolean, bg: Int) {
        val a = sp.active(t)
        type(k.face ?: if (k.vibe == Vibe.NATIVE) sys else plainFace, Tok.BODY, Tok.WHITE)
        val rows = window(wrapIdx(sp.words, sp.words.indices, W * Tok.CAPTION_W), a)
        val lh = Tok.BODY * s * Tok.LEADING
        capSize = Tok.BODY; capLh = lh
        val base = k.ink ?: if (box) inkOn(bg) else Tok.WHITE
        rows.forEachIndexed { ri, r ->
            val y = ay - (rows.size - 1 - ri) * lh
            if (box) {
                val rw = p.measureText(sp.words.slice(r).joinToString(" ")) + 2 * Tok.SP_M * s
                ink(bg, 1f)
                c.drawRoundRect(RectF(W / 2 - rw / 2, y - lh / 2, W / 2 + rw / 2, y + lh / 2), Tok.R_SM * s, Tok.R_SM * s, p)
                mark(W / 2 - rw / 2, y - lh / 2, W / 2 + rw / 2, y + lh / 2)
            } else {
                soft()
            }
            row(c, sp.words, r, W / 2, y, lh) { i -> wordInk(k, i, a, base, Tok.DIM) }
            p.clearShadowLayer()
        }
    }

    /** Film: lowercase mono, typed on a character at a time (a line is all in by T_EMPH); the word being said in amber. */
    private fun typeCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap) {
        val a = sp.active(t)
        val words = sp.words.map { it.lowercase() }
        type(k.face ?: monoMed, Tok.BODY, Tok.WHITE)
        val rows = window(wrapIdx(words, words.indices, W * Tok.CAPTION_W), a)
        val lh = Tok.BODY * s * Tok.LEADING
        capSize = Tok.BODY; capLh = lh
        val chars = words.sumOf { it.length + 1 }
        val per = min(Tok.TYPE_CHAR_MS.toFloat(), Tok.T_EMPH.toFloat() / chars)
        val typed = ((t - sp.start) / per).toInt()
        val at = IntArray(words.size).also { var n = 0; for (i in words.indices) { it[i] = n; n += words[i].length + 1 } }
        val space = p.measureText(" ")
        soft()
        rows.forEachIndexed { ri, r ->
            val y = ay - (rows.size - 1 - ri) * lh
            val total = p.measureText(words.slice(r).joinToString(" "))
            var x = W / 2 - total / 2
            mark(x, y - lh / 2, x + total, y + lh / 2)
            for (i in r) {
                val n = (typed - at[i]).coerceIn(0, words[i].length)
                if (n > 0) { wordInk(k, i, a, k.ink ?: Tok.WHITE, Tok.DIM); c.drawText(words[i].take(n), x, mid(y), p) }
                x += p.measureText(words[i]) + space
            }
        }
    }

    /** Editorial: a paper strip per row, each nudged and tilted a touch, sliding in; a red underline draws under the word being said. */
    private fun stripCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap, paper: Int) {
        val a = sp.active(t)
        val padX = Tok.SP_STRIP * s
        val padY = Tok.SP_S * s
        type(k.face ?: geist, Tok.BODY, k.ink ?: inkOn(paper))
        val all = wrapIdx(sp.words, sp.words.indices, W * Tok.CAPTION_W - 2 * padX)
        val rows = window(all, a)
        val first = all.indexOf(rows.first())
        val rh = p.descent() - p.ascent() + 2 * padY
        val gap = Tok.SP_XS * s
        capSize = Tok.BODY; capLh = rh
        val base = fade
        val space = p.measureText(" ")
        rows.forEachIndexed { ri, r ->
            val cy = ay - (rows.size - 1 - ri) * (rh + gap)
            // The same nudge and tilt every frame: seeded by the line and the row.
            val rnd = java.util.Random(sp.index * STRIP_SEED + first + ri)
            val dx = (rnd.nextFloat() * 2 - 1) * STRIP_NUDGE * s
            val rot = (rnd.nextFloat() * 2 - 1) * STRIP_TILT
            val q = Tok.out(Tok.prog(t - sp.start - ri * Tok.STAGGER, Tok.T_BASE))
            val tw = p.measureText(sp.words.slice(r).joinToString(" "))
            val rw = tw + 2 * padX
            fade = base * q
            c.save(); c.translate(W / 2 + dx - (1 - q) * Tok.SP_L * s, cy); c.rotate(rot)
            ink(paper, 1f)
            c.drawRect(-rw / 2, -rh / 2, rw / 2, rh / 2, p)
            var x = -tw / 2
            for (i in r) {
                val ww = p.measureText(sp.words[i])
                ink(k.ink ?: inkOn(paper), 1f)
                c.drawText(sp.words[i], x, mid(0f), p)
                if (k.karaoke && i == a) {
                    val u = Tok.out(Tok.prog(t - sp.starts[i], Tok.T_MICRO))
                    ink(k.hi ?: Tok.RED, 1f)
                    val uy = mid(0f) + p.descent() * STRIP_UNDER
                    c.drawRect(x, uy, x + ww * u, uy + Tok.RULE_M * s, p)
                }
                x += ww + space
            }
            c.restore()
            mark(W / 2 + dx - rw / 2, cy - rh / 2, W / 2 + dx + rw / 2, cy + rh / 2)
        }
        fade = base
    }

    /** One word's colour: the highlight on the word being said, or (no highlight colour) the others dimmed. */
    private fun wordInk(k: Cap, i: Int, active: Int, base: Int, dim: Float) {
        // A highlight too close to the words' own colour (paper on white) reads as none: dim instead.
        val hi = k.hi?.takeIf { distinct(it, base) }
        when {
            !k.karaoke -> ink(base, 1f)
            i == active -> ink(hi ?: base, 1f)
            hi != null -> ink(base, 1f)
            else -> ink(base, dim)
        }
    }

    private fun distinct(a: Int, b: Int): Boolean {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return dr * dr + dg * dg + db * db > DISTINCT * DISTINCT
    }

    private fun isKey(sp: Spoken, i: Int): Boolean {
        val key = sp.key ?: return false
        return sp.words[i].trim { !it.isLetterOrDigit() }.equals(key.trim { !it.isLetterOrDigit() }, ignoreCase = true)
    }

    // ---- cover, section text, timeline text ----------------------------------------------------

    /**
     * The vibe's display type, centred on ([cx], [cy]): hooks, section text, the Style text look,
     * the intro title and covers. [ms] is the time since it appeared (null = still, fully in);
     * [above] and [below] are small caps labels.
     */
    private fun headline(c: Canvas, vibe: Vibe, text: String, cx: Float, cy: Float, size: Float, ms: Float?, alpha: Float, above: String? = null, below: String? = null, maxW: Float = W * Tok.TITLE_W, color: Int? = null, left: Boolean = true) {
        if (vibe == Vibe.HYPE) return posterHeadline(c, text, cx, cy, size, ms, alpha, above, below, maxW, color, left)
        if (vibe == Vibe.SPEEDTRAP) return dashHeadline(c, text, cx, cy, size, ms, alpha, above, below, maxW, color, left)
        val t = ms ?: STILL
        val raw = text.trim().split(WS).filter { it.isNotEmpty() }
        if (raw.isEmpty() || alpha <= 0f) return
        val face = headFace(vibe)
        val words = if (vibe == Vibe.HYPE) raw.map { it.uppercase() } else raw
        val track = when (vibe) {
            Vibe.VLOG -> if (size >= Tok.CAP_L) Tok.TRACK_TIGHT else 0f
            Vibe.CINE -> CINE_TRACK * (1 - Tok.inOut(Tok.prog(t, Tok.T_SLOW)))
            else -> 0f
        }
        val block = vibe == Vibe.CHILL || vibe == Vibe.EDITORIAL
        val padX = if (block) Tok.SP_XL * s else 0f
        val padY = if (block) Tok.SP_M * s else 0f
        val (z, rows) = rowsAt(face, words, size, maxW - 2 * padX, track)
        val lh = z * s * Tok.LEADING_TIGHT
        val blockH = rows.size * lh
        // A label above starts below the info slot (the speed badge): the block moves down to make room.
        val labelTop = cy - blockH / 2 - padY - Tok.SP_L * s - Tok.LABEL * s
        val cy = cy + if (above != null) max(0f, H * Tok.INFO_BOTTOM + Tok.SP_S * s - labelTop) else 0f
        val top = cy - blockH / 2
        fun rowY(ri: Int) = top + lh / 2 + ri * lh
        fun line(r: IntRange) = words.slice(r).joinToString(" ")
        type(face, z, Tok.WHITE, Paint.Align.CENTER, track)
        val widest = rows.maxOf { p.measureText(line(it)) }
        var labelA = alpha * Tok.out(Tok.prog(t, Tok.T_BASE))
        when (vibe) {
            Vibe.HYPE -> {}
            Vibe.CINE -> {
                fade = alpha * Tok.inOut(Tok.prog(t, Tok.T_SLOW))
                labelA = fade
                soft()
                rows.forEachIndexed { ri, r -> ink(color ?: accent(vibe), 1f); c.drawText(line(r), cx, mid(rowY(ri)), p) }
            }
            Vibe.VLOG -> rows.forEachIndexed { ri, r ->
                val q = Tok.out(Tok.prog(t - ri * Tok.STAGGER, Tok.T_EMPH))
                fade = alpha
                c.save()
                if (q < 1f) c.clipRect(0f, rowY(ri) - lh / 2, W, rowY(ri) + lh / 2)
                soft(); ink(color ?: Tok.WHITE, 1f)
                c.drawText(line(r), cx, mid(rowY(ri) + (1 - q) * lh), p)
                c.restore()
            }
            Vibe.CHILL -> {
                val q = Tok.prog(t, Tok.T_EMPH)
                val pop = POP_FROM + (1 - POP_FROM) * Tok.spring(q)
                fade = alpha * Tok.out(q)
                val bw = widest + 2 * padX
                val bh = blockH + 2 * padY
                val r = if (rows.size == 1) min(Tok.R_PILL * s, bh / 2) else Tok.R_MD * s
                c.save(); c.translate(cx, cy); c.rotate(PLAYFUL_TITLE_TILT); c.scale(pop, pop)
                ink(accent(vibe), 1f); c.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), r, r, p)
                rows.forEachIndexed { ri, row -> ink(color ?: inkOn(accent(vibe)), 1f); c.drawText(line(row), 0f, mid(rowY(ri) - cy), p) }
                c.restore()
            }
            Vibe.FILM -> {
                fade = alpha
                val chars = words.sumOf { it.length + 1 }
                val per = min(Tok.TYPE_CHAR_MS.toFloat(), Tok.T_EMPH.toFloat() / chars)
                var typed = (t / per).toInt()
                p.textAlign = Paint.Align.LEFT
                soft()
                rows.forEachIndexed { ri, r ->
                    val full = line(r)
                    if (typed > 0) { ink(color ?: Tok.WHITE, 1f); c.drawText(full.take(typed), cx - p.measureText(full) / 2, mid(rowY(ri)), p) }
                    typed -= full.length + 1
                }
            }
            Vibe.EDITORIAL -> {
                val q = Tok.out(Tok.prog(t, Tok.T_BASE))
                fade = alpha * q
                val dx = -(1 - q) * Tok.SP_L * s
                val bw = widest + 2 * padX
                val bh = blockH + 2 * padY
                ink(Tok.PAPER, 1f); c.drawRect(cx - bw / 2 + dx, cy - bh / 2, cx + bw / 2 + dx, cy + bh / 2, p)
                rows.forEachIndexed { ri, r -> ink(color ?: Tok.INK, 1f); c.drawText(line(r), cx + dx, mid(rowY(ri)), p) }
                // The red rule draws in under the block.
                val rq = Tok.out(Tok.prog(t - Tok.STAGGER, Tok.T_EMPH))
                ink(accent(vibe), 1f)
                c.drawRect(cx - bw / 2 + dx, cy + bh / 2 + Tok.SP_S * s, cx - bw / 2 + dx + bw * rq, cy + bh / 2 + (Tok.SP_S + Tok.RULE_L) * s, p)
            }
            Vibe.NATIVE -> {
                fade = alpha * Tok.out(Tok.prog(t, Tok.T_BASE))
                labelA = fade
                soft()
                rows.forEachIndexed { ri, r -> ink(color ?: Tok.WHITE, 1f); c.drawText(line(r), cx, mid(rowY(ri)), p) }
            }
            Vibe.SPEEDTRAP -> {}
        }
        fade = 1f
        val ext = padY + (if (vibe == Vibe.EDITORIAL) (Tok.SP_S + Tok.RULE_L) * s else 0f)
        above?.let { smallLabel(c, vibe, it, cx, top - padY - Tok.SP_L * s - Tok.LABEL * s / 2, labelA) }
        below?.let { smallLabel(c, vibe, it, cx, top + blockH + ext + Tok.SP_L * s + Tok.LABEL * s / 2, labelA) }
    }

    /** A small caps label in the vibe's label face (Film: amber). */
    private fun smallLabel(c: Canvas, vibe: Vibe, text: String, cx: Float, cy: Float, a: Float, align: Paint.Align = Paint.Align.CENTER) {
        if (a <= 0f) return
        val track = if (vibe == Vibe.EDITORIAL) Tok.TRACK_CAPS_EDITORIAL else Tok.TRACK_CAPS
        type(labelFace(vibe), Tok.LABEL, Tok.WHITE, align, track)
        fade = a
        soft()
        ink(when (vibe) { Vibe.FILM, Vibe.CINE -> accent(vibe); else -> Tok.WHITE }, 1f)
        c.drawText(text.uppercase(), cx, mid(cy), p)
        p.clearShadowLayer()
        fade = 1f
    }

    /**
     * A Reel cover's text, still, in the vibe's headline. Kept inside the middle 3:4 so
     * Instagram's profile grid doesn't crop it.
     */
    fun drawCoverText(c: Canvas, vibe: Vibe, line: String, cy: Float = H * COVER_Y, shade: Boolean = true) {
        if (line.isBlank()) return
        // A soft shade under the text so it reads on any frame.
        if (shade) {
            p.reset()
            p.shader = LinearGradient(0f, cy - H * COVER_SHADE, 0f, cy + H * COVER_SHADE, Color.TRANSPARENT, Tok.alpha(Tok.INK, Tok.A_MID), Shader.TileMode.CLAMP)
            c.drawRect(0f, cy - H * COVER_SHADE, W, cy + H * COVER_SHADE, p)
            p.shader = null
        }
        headline(c, vibe, line, W / 2, cy, if (vibe == Vibe.FILM || vibe == Vibe.NATIVE) Tok.CAP_L else Tok.HERO, null, 1f)
    }

    /** A section's on-screen text: the same path as timeline text in the Style look, in the title slot. */
    fun drawSectionText(c: Canvas, vibe: Vibe, text: String, localMs: Long) {
        if (localMs >= Tok.SECTION_TEXT_MS) return
        drawTextItem(c, vibe, TextItem(SECTION_ID, 0, Tok.SECTION_TEXT_MS, text), localMs, Tok.SECTION_TEXT_MS)
    }

    /** Timeline text: in its look, place (kept in the safe area), size and angle, with its animations in and out. */
    fun drawTextItem(c: Canvas, vibe: Vibe, t: TextItem, localMs: Long, durMs: Long) {
        fade = 1f
        val ms = tq(vibe, localMs.toFloat()).toLong()
        val f = TextTools.animate(t, ms, durMs, t.text.length)
        if (f.alpha <= 0f || f.chars == 0) return
        val shown = t.text.take(f.chars)
        val (x, y) = Tok.place(t.x, t.y + f.dy)
        val px = W * x
        val py = H * y
        c.save()
        c.rotate(t.rotation, px, py)
        val k = t.size * f.scale
        c.scale(k, k, px, py)
        when (t.look) {
            TextLook.STYLE -> {
                // The vibe's own entrance stands in for a plain fade in.
                val styleIn = t.animIn == TextAnim.FADE
                val a = if (styleIn) TextTools.animate(t.copy(animIn = TextAnim.NONE), ms, durMs, t.text.length).alpha else f.alpha
                headline(c, vibe, shown, px, py, if (vibe == Vibe.HYPE) Tok.TITLE else hookSize(vibe), if (styleIn) ms.toFloat() else null, a, color = t.color, left = t.id == SECTION_ID)
            }
            TextLook.PLAIN -> styledText(c, shown, px, py, t.align, faceOf(t.font) ?: if (vibe == Vibe.NATIVE) sys else plainFace, Tok.CAP, t.color ?: brand?.color ?: Tok.WHITE, f.alpha, shadow = true)
            TextLook.OUTLINE -> styledText(c, shown.uppercase(), px, py, t.align, faceOf(t.font) ?: anton, Tok.TITLE, t.color ?: Tok.WHITE, f.alpha, outline = true)
            TextLook.HAND -> styledText(c, shown, px, py, t.align, faceOf(t.font) ?: marker, Tok.CAP_L, t.color ?: Tok.WHITE, f.alpha, shadow = true)
            TextLook.BOX -> {
                val bg = t.color ?: Tok.WHITE
                val ink = inkOn(bg)
                type(faceOf(t.font) ?: plainFace, Tok.BODY, ink, Paint.Align.CENTER)
                val words = shown.split(WS).filter { it.isNotEmpty() }
                val rows = wrapIdx(words, words.indices, W * Tok.TITLE_W)
                val lh = Tok.BODY * s * Tok.LEADING
                val bw = rows.maxOf { p.measureText(words.slice(it).joinToString(" ")) } + 2 * Tok.SP_XL * s
                val bh = rows.size * lh + 2 * Tok.SP_M * s
                val left = when (t.align) { TextAlignment.LEFT -> px; TextAlignment.CENTER -> px - bw / 2; TextAlignment.RIGHT -> px - bw }
                fade = f.alpha
                ink(bg, 1f); c.drawRoundRect(RectF(left, py - bh / 2, left + bw, py + bh / 2), Tok.R_MD * s, Tok.R_MD * s, p)
                ink(ink, 1f)
                rows.forEachIndexed { i, r -> c.drawText(words.slice(r).joinToString(" "), left + bw / 2, mid(py + (i - (rows.size - 1) / 2f) * lh), p) }
            }
        }
        c.restore()
        fade = 1f
    }

    /** Text in rows around ([x], [y]), aligned, with the soft shadow or an ink outline (8% of the size). */
    private fun styledText(c: Canvas, line: String, x: Float, y: Float, align: TextAlignment, face: Typeface, size: Float, color: Int, alpha: Float, shadow: Boolean = false, outline: Boolean = false) {
        val pAlign = when (align) { TextAlignment.LEFT -> Paint.Align.LEFT; TextAlignment.CENTER -> Paint.Align.CENTER; TextAlignment.RIGHT -> Paint.Align.RIGHT }
        type(face, size, color, pAlign)
        val words = line.split(WS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        val rows = wrapIdx(words, words.indices, W * Tok.TITLE_W)
        val lh = size * s * Tok.LEADING_TIGHT
        fade = alpha
        rows.forEachIndexed { i, r ->
            val by = mid(y + (i - (rows.size - 1) / 2f) * lh)
            val txt = words.slice(r).joinToString(" ")
            if (outline) {
                p.style = Paint.Style.STROKE; p.strokeWidth = size * s * OUTLINE; ink(Tok.INK, 1f)
                c.drawText(txt, x, by, p)
                p.style = Paint.Style.FILL
            }
            if (shadow) soft()
            ink(color, 1f)
            c.drawText(txt, x, by, p)
            p.clearShadowLayer()
        }
    }

    /** A sticker: live speed or lean, an emoji, an arrow or a circle; fades and scales in, fades out. */
    fun drawSticker(c: Canvas, st: StickerItem, localMs: Long, kmh: Int, lean: Int?, vibe: Vibe = Vibe.HYPE) {
        val ms = tq(vibe, localMs.toFloat())
        val dur = (st.endMs - st.startMs).toFloat()
        val q = Tok.out(Tok.prog(ms, Tok.T_BASE))
        val a = q * (1 - Tok.inn(1 - Tok.prog(dur - ms, Tok.exit(Tok.T_BASE))))
        if (a <= 0f) return
        val (x, y) = Tok.place(st.x, st.y)
        val px = W * x
        val py = H * y
        val acc = accent(vibe)
        c.save()
        c.rotate(st.rotation, px, py)
        val z = st.size * (STICKER_FROM + (1 - STICKER_FROM) * q)
        c.scale(z, z, px, py)
        fade = a
        when (st.kind) {
            StickerKind.SPEED -> {
                val r = DISC * s
                val arc = RectF(px - r + Tok.SP_M * s, py - r + Tok.SP_M * s, px + r - Tok.SP_M * s, py + r - Tok.SP_M * s)
                p.reset(); p.isAntiAlias = true
                ink(Tok.INK, Tok.A_HIGH); c.drawCircle(px, py, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = Tok.SP_M * s; p.strokeCap = Paint.Cap.ROUND
                ink(Tok.WHITE, Tok.A_LOW); c.drawArc(arc, ARC_FROM, ARC_SWEEP, false, p)
                ink(acc, 1f); c.drawArc(arc, ARC_FROM, ARC_SWEEP * (kmh.coerceAtLeast(0) / ARC_TOP_KMH).coerceIn(0f, 1f), false, p)
                p.style = Paint.Style.FILL
                type(numFace(vibe), Tok.CAP_L, Tok.WHITE, Paint.Align.CENTER); ink(Tok.WHITE, 1f)
                c.drawText(if (kmh >= 0) "$kmh" else "–", px, mid(py - Tok.SP_XS * s), p)
                type(labelFace(vibe), Tok.LABEL, Tok.WHITE, Paint.Align.CENTER, Tok.TRACK_CAPS); ink(Tok.WHITE, 1f)
                c.drawText("KM/H", px, mid(py + r / 2), p)
            }
            StickerKind.LEAN -> {
                val r = DISC * s
                p.reset(); p.isAntiAlias = true
                ink(Tok.INK, Tok.A_HIGH); c.drawCircle(px, py, r, p)
                val deg = (lean ?: 0).coerceIn(-LEAN_MAX, LEAN_MAX)
                // A little bike leaning with the ride.
                val foot = py + r * LEAN_FOOT
                c.save(); c.rotate(deg.toFloat(), px, foot)
                ink(Tok.WHITE, 1f); p.strokeWidth = Tok.SP_S * s; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(px, foot, px, py - r / 2, p)
                c.drawCircle(px, py - r / 2 - Tok.SP_S * s, Tok.SP_S * s, p)
                c.restore()
                type(numFace(vibe), Tok.BODY, acc, Paint.Align.CENTER); ink(acc, 1f)
                c.drawText(if (lean != null) "${abs(deg)}°" else "–", px, mid(py + r * LEAN_LABEL), p)
            }
            StickerKind.EMOJI -> {
                type(Typeface.DEFAULT, EMOJI, Tok.WHITE, Paint.Align.CENTER); ink(Tok.WHITE, 1f)
                c.drawText(st.text.ifEmpty { "🔥" }, px, mid(py), p)
            }
            StickerKind.ARROW -> {
                p.reset(); p.isAntiAlias = true
                soft(); ink(Tok.WHITE, 1f)
                val u = Tok.SP_S * s
                val path = android.graphics.Path().apply {
                    moveTo(px - 14 * u, py - 2 * u); lineTo(px + 4 * u, py - 2 * u); lineTo(px + 4 * u, py - 6 * u)
                    lineTo(px + 14 * u, py); lineTo(px + 4 * u, py + 6 * u); lineTo(px + 4 * u, py + 2 * u); lineTo(px - 14 * u, py + 2 * u); close()
                }
                c.drawPath(path, p)
                p.clearShadowLayer()
            }
            StickerKind.CIRCLE -> {
                // A ring that pulses gently, to point at something.
                val pulse = 1f + PULSE * sin(2 * Math.PI.toFloat() * ms / Tok.T_SLOW)
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = Tok.SP_S * s
                ink(acc, 1f)
                c.drawCircle(px, py, RING * s * pulse, p)
                p.style = Paint.Style.FILL
            }
        }
        c.restore()
        fade = 1f
    }

    // ---- speed badge -------------------------------------------------------------------------

    /** The speed in the info slot (top-left), in the vibe's own way; [kmh] < 0 = none. */
    private fun speedBadge(c: Canvas, vibe: Vibe, kmh: Int, clock: String, rev: Float? = null) {
        if (kmh < 0) return
        fade = 1f
        val x = W * Tok.INFO_X
        val y = H * Tok.INFO_Y
        when (vibe) {
            Vibe.HYPE -> {
                // The number drawn as an outline, the unit small in yellow.
                type(archivo, Tok.CAP_L, Tok.WHITE, track = POSTER_TRACK)
                val n = "$kmh"
                val nb = mid(y)
                p.style = Paint.Style.STROKE; p.strokeWidth = POSTER_OUTLINE * s
                ink(Tok.WHITE, 1f); c.drawText(n, x, nb, p)
                val nw = p.measureText(n)
                type(geistMed, Tok.LABEL, Tok.YELLOW, track = Tok.TRACK_CAPS)
                soft(); ink(accent(vibe), 1f); c.drawText("KM/H", x + nw + Tok.SP_S * s, nb, p)
            }
            Vibe.CINE -> {
                type(geistMed, Tok.LABEL, accent(vibe), track = Tok.TRACK_CAPS)
                soft(); ink(accent(vibe), Tok.QUIET)
                c.drawText("$kmh KM/H", x, mid(y), p)
            }
            Vibe.CHILL -> {
                val r = BADGE_DISC * s
                c.save(); c.translate(x + r, y); c.rotate(PLAYFUL_BADGE_TILT)
                p.reset(); p.isAntiAlias = true; p.color = accent(vibe); c.drawCircle(0f, 0f, r, p)
                type(fredoka, Tok.BODY, inkOn(accent(vibe)), Paint.Align.CENTER); c.drawText("$kmh", 0f, mid(-Tok.SP_S * s), p)
                type(fredoka, Tok.LABEL, inkOn(accent(vibe)), Paint.Align.CENTER); c.drawText("km/h", 0f, mid(r / 2 - Tok.SP_XS * s), p)
                c.restore()
            }
            Vibe.VLOG -> {
                type(geist, Tok.LABEL_L, Tok.WHITE)
                val label = "$kmh km/h"
                val bw = p.measureText(label) + 2 * Tok.SP_M * s
                val bh = Tok.LABEL_L * s + 2 * Tok.SP_S * s
                ink(Tok.INK, Tok.A_LOW); c.drawRoundRect(RectF(x, y - bh / 2, x + bw, y + bh / 2), bh / 2, bh / 2, p)
                ink(Tok.WHITE, 1f); c.drawText(label, x + Tok.SP_M * s, mid(y), p)
            }
            Vibe.FILM -> {
                type(mono, Tok.LABEL_L, accent(vibe))
                soft(); ink(accent(vibe), 1f)
                c.drawText(listOf("$kmh km/h", clock).filter { it.isNotBlank() }.joinToString(" · ").uppercase(), x, mid(y), p)
            }
            Vibe.EDITORIAL -> {
                type(geistMed, Tok.LABEL, Tok.INK, track = Tok.TRACK_CAPS_EDITORIAL)
                val label = "$kmh KM/H"
                val bw = p.measureText(label) + 2 * Tok.SP_S * s
                val bh = Tok.LABEL * s + 2 * Tok.SP_S * s
                ink(Tok.PAPER, 1f); c.drawRect(x, y - bh / 2, x + bw, y + bh / 2, p)
                ink(Tok.INK, 1f); c.drawText(label, x + Tok.SP_S * s, mid(y), p)
            }
            Vibe.NATIVE -> {
                type(sysReg, Tok.LABEL_L, Tok.WHITE)
                soft(); ink(Tok.WHITE, 1f)
                c.drawText("$kmh km/h", x, mid(y), p)
            }
            Vibe.SPEEDTRAP -> {
                // The bike's dash: the number, and beside it a rev bar (from the engine sound; else the speed) over "KM/H".
                val bh = DASH_BADGE_H * s
                type(orbitron, Tok.CAP, Tok.WHITE)
                val nw = p.measureText("$kmh")
                val barW = DASH_SEGMENTS * (DASH_SEG_W + DASH_SEG_GAP) * s
                val bw = nw + barW + (2 * Tok.SP_M + Tok.SP_S) * s
                panel(c, x, y - bh / 2, x + bw, y + bh / 2, accent(vibe))
                val bx = x + Tok.SP_M * s + nw + Tok.SP_S * s
                revBar(c, bx, y - bh / 2 + Tok.SP_S * s, rev ?: (kmh / ARC_TOP_KMH).coerceIn(0f, 1f), accent(vibe))
                type(orbitron, Tok.CAP, Tok.WHITE); ink(Tok.WHITE, 1f)
                val base = y + bh / 2 - Tok.SP_S * s
                c.drawText("$kmh", x + Tok.SP_M * s, base, p)
                type(orbitron, Tok.LABEL, Tok.WHITE, track = Tok.TRACK_CAPS); ink(Tok.WHITE, Tok.DIM)
                c.drawText("KM/H", bx, base, p)
            }
        }
        p.clearShadowLayer()
    }

    // ---- title and stats ---------------------------------------------------------------------

    /** The opening: the route draws itself under the title (or the title sits over the first clip). */
    fun drawTitle(c: Canvas, f: FrameAt, card: RideCard, map: Boolean) {
        fade = 1f
        val t = tq(f.vibe, f.localMs.toFloat())
        val dur = f.durMs.toFloat()
        val ex = Tok.exit(Tok.T_BASE)
        val out = 1 - Tok.inn(Tok.prog(t - (dur - ex), ex))
        if (map) {
            mapBackground(c)
            val q = Tok.inOut(min(1f, t / (dur * ROUTE_DRAW)))
            val upTo = max(2, (card.route.size * q).toInt())
            c.save(); c.translate(0f, H * ROUTE_DY)
            val pt = drawRoute(c, card.route, upTo, ROUTE_ZOOM + ROUTE_ZOOM_IN * (1 - q), Tok.SP_S * s, ROUTE_PAD)
            if (pt != null) {
                val acc = accent(f.vibe)
                p.reset(); p.isAntiAlias = true
                p.shader = RadialGradient(pt.first, pt.second, GLOW * s, Tok.alpha(acc, Tok.A_HIGH), Tok.alpha(acc, 0f), Shader.TileMode.CLAMP)
                c.drawCircle(pt.first, pt.second, GLOW * s, p); p.shader = null
                p.color = Tok.WHITE; c.drawCircle(pt.first, pt.second, Tok.SP_S * s, p)
            }
            c.restore()
            headline(c, f.vibe, card.title, W / 2, H * Tok.TITLE_Y, titleSize(f.vibe), t, out, below = card.subtitle)
        } else {
            veil(c, Tok.INK, Tok.A_MID)
            headline(c, f.vibe, card.title, W / 2, H * TITLE_MID, titleSize(f.vibe), t, out, below = card.subtitle)
        }
        edge(f)?.let { transition(c, f, it) }
    }

    /** The ending: the ride's numbers counting up in the vibe's type, the route faint behind them, the brand or the mark. */
    fun drawStats(c: Canvas, f: FrameAt, card: RideCard, map: Boolean, watermark: Boolean) {
        fade = 1f
        val v = f.vibe
        val t = tq(v, f.localMs.toFloat())
        val paperGround = v == Vibe.EDITORIAL
        val ground = if (paperGround) Tok.PAPER else Tok.INK
        val fg = if (paperGround) Tok.INK else Tok.WHITE
        p.reset(); p.color = ground
        c.drawRect(0f, 0f, W, H, p)
        if (v == Vibe.FILM) grain(c, v, f.localMs.toFloat())
        if (map) {
            c.saveLayerAlpha(0f, 0f, W, H, (Tok.A_LOW * 255).toInt())
            c.translate(0f, H * STATS_ROUTE_DY)
            drawRoute(c, card.route, card.route.size, 1f, Tok.SP_XS * s, ROUTE_PAD)
            c.restore()
        }
        when (v) {
            Vibe.HYPE -> posterStats(c, card, t)
            Vibe.SPEEDTRAP -> dashStats(c, card, t, accent(v))
            else -> gridStats(c, v, card, t, fg, paperGround)
        }
        fade = Tok.out(Tok.prog(t - Tok.T_SLOW, Tok.T_EMPH))
        val l = logo
        if (l != null) {
            // The rider's own logo instead of the Keppo mark.
            val hgt = LOGO * s
            val wid = hgt * l.width / l.height.coerceAtLeast(1)
            c.drawBitmap(l, null, RectF(W / 2 - wid / 2, H * LOGO_Y - hgt / 2, W / 2 + wid / 2, H * LOGO_Y + hgt / 2), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (fade * 255).toInt() })
        }
        val handle = brand?.handle?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("@")) it else "@$it" }
        val mark = handle ?: if (watermark && l == null) "made with Keppo Moto" else null
        mark?.let {
            type(labelFace(v), Tok.LABEL_L, fg, Paint.Align.CENTER)
            ink(if (handle != null) brand?.color ?: fg else fg, if (handle != null) 1f else Tok.A_HIGH)
            c.drawText(it, W / 2, mid(H * MARK_Y), p)
        }
        fade = 1f
        edge(f)?.let { transition(c, f, it) }
    }

    /** The ride's numbers in a 2×2 grid, counting up, in the vibe's type. */
    private fun gridStats(c: Canvas, v: Vibe, card: RideCard, t: Float, fg: Int, paperGround: Boolean) {
        headline(c, v, card.title, W / 2, H * Tok.TITLE_Y, hookSize(v), t, 1f, color = if (paperGround) Tok.INK else null)
        if (v == Vibe.EDITORIAL) {
            // A red rule between the rows.
            p.reset(); p.color = accent(v)
            val ry = H * (STATS_ROW_1 + STATS_ROW_2) / 2 + Tok.SP_XL * s
            c.drawRect(W * Tok.INFO_X * 2, ry, W * (1 - Tok.INFO_X * 2), ry + Tok.RULE_S * s, p)
        }
        val numSize = when (v) { Vibe.FILM, Vibe.NATIVE, Vibe.CHILL -> Tok.TITLE; else -> Tok.HERO }
        card.stats.take(4).forEachIndexed { i, (value, unit) ->
            val delay = i * Tok.STAGGER * 2
            val q = Tok.prog(t - delay, Tok.T_EMPH)
            val e = if (v == Vibe.HYPE) Tok.springHard(q) else Tok.out(q)
            val shown = countUp(value, Tok.out(Tok.prog(t - delay, Tok.T_SLOW)))
            val cx = W * (if (i % 2 == 1) STATS_RIGHT else STATS_LEFT)
            val cy = H * (if (i < 2) STATS_ROW_1 else STATS_ROW_2)
            c.save(); c.translate(cx, cy + (1 - e) * Tok.SP_L * s)
            fade = Tok.out(q)
            if (v == Vibe.CHILL) {
                p.reset(); p.isAntiAlias = true; ink(Tok.PAPER, 1f)
                c.drawRoundRect(RectF(-W * TILE_W, -H * TILE_H, W * TILE_W, H * TILE_H), Tok.R_MD * s, Tok.R_MD * s, p)
            }
            val numInk = if (v == Vibe.CHILL) Tok.INK else if (v == Vibe.CINE) accent(v) else fg
            val z = fit(numFace(v), shown, numSize, W * (STATS_RIGHT - STATS_LEFT) - Tok.SP_L * s, Tok.CAP)
            type(numFace(v), z, numInk, Paint.Align.CENTER); ink(numInk, 1f)
            c.drawText(shown, 0f, mid(-Tok.SP_M * s), p)
            val numBottom = -Tok.SP_M * s + (p.descent() - p.ascent()) / 2
            val (uFace, uSize, uInk, uA) = when (v) {
                Vibe.HYPE -> Quad(geistMed, Tok.LABEL_L, accent(v), 1f)
                Vibe.CINE -> Quad(geistMed, Tok.LABEL, accent(v), Tok.QUIET)
                Vibe.CHILL -> Quad(fredoka, Tok.LABEL_L, Tok.INK, 1f)
                Vibe.FILM -> Quad(mono, Tok.LABEL, accent(v), 1f)
                Vibe.EDITORIAL -> Quad(geistMed, Tok.LABEL, Tok.INK, 1f)
                Vibe.VLOG, Vibe.NATIVE -> Quad(labelFace(v), Tok.LABEL_L, Tok.WHITE, Tok.DIM)
                Vibe.SPEEDTRAP -> Quad(orbitron, Tok.LABEL, accent(v), 1f)
            }
            val caps = v == Vibe.HYPE || v == Vibe.CINE || v == Vibe.FILM || v == Vibe.EDITORIAL
            type(uFace, uSize, uInk, Paint.Align.CENTER, if (caps) (if (v == Vibe.EDITORIAL) Tok.TRACK_CAPS_EDITORIAL else Tok.TRACK_CAPS) else 0f)
            ink(uInk, uA)
            c.drawText(if (caps) unit.uppercase() else unit, 0f, numBottom + Tok.SP_S * s - p.ascent(), p)
            c.restore()
        }
    }

    /** Bold's stats, a poster: the title flush left, top speed as one huge number, the rest in a row below. */
    private fun posterStats(c: Canvas, card: RideCard, t: Float) {
        posterHeadline(c, card.title, W / 2, H * Tok.TITLE_Y, Tok.TITLE, t, 1f, null, null, W * Tok.TITLE_W, null, left = true)
        val x = W * Tok.INFO_X
        val hero = card.stats.indexOfFirst { "top" in it.second.lowercase() }.takeIf { it >= 0 } ?: 0
        val (value, unit) = card.stats.getOrNull(hero) ?: return
        val q = Tok.out(Tok.prog(t, Tok.T_EMPH))
        fade = q
        val z = fit(archivo, value, POSTER_NUMBER, W * Tok.TITLE_W, Tok.HERO)
        type(archivo, z, Tok.WHITE, track = POSTER_TRACK)
        val sc = POSTER_FROM - (POSTER_FROM - 1) * q
        c.save(); c.scale(sc, sc, x, H * POSTER_NUM_Y)
        ink(Tok.WHITE, 1f); c.drawText(countUp(value, Tok.out(Tok.prog(t, Tok.T_SLOW))), x - Tok.SP_XS * s, mid(H * POSTER_NUM_Y), p)
        c.restore()
        type(archivo, Tok.CAP, accent(Vibe.HYPE), track = POSTER_TRACK)
        ink(accent(Vibe.HYPE), 1f); c.drawText(unit.uppercase(), x, mid(H * POSTER_UNIT_Y), p)
        // The other numbers, smaller, in a row.
        val rest = card.stats.take(4).filterIndexed { i, _ -> i != hero }
        val colW = W * Tok.TITLE_W / rest.size.coerceAtLeast(1)
        rest.forEachIndexed { i, (v, u) ->
            fade = Tok.out(Tok.prog(t - (i + 1) * Tok.STAGGER * 2, Tok.T_BASE))
            val cx = x + i * colW
            type(archivo, Tok.CAP, Tok.WHITE, track = POSTER_TRACK); ink(Tok.WHITE, 1f)
            c.drawText(countUp(v, Tok.out(Tok.prog(t - (i + 1) * Tok.STAGGER * 2, Tok.T_SLOW))), cx, mid(H * POSTER_ROW_Y), p)
            type(geistMed, Tok.LABEL, Tok.WHITE, track = Tok.TRACK_CAPS); ink(Tok.WHITE, Tok.DIM)
            c.drawText(u.uppercase(), cx, mid(H * POSTER_ROW_Y + Tok.CAP * s * Tok.LEADING_TIGHT), p)
        }
        fade = 1f
    }

    // ---- Speedtrap: the bike's dash ------------------------------------------------------------

    /** A dash panel: dark, chamfered corners, a line of [edge] along its top. */
    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, edge: Int) {
        val k = DASH_CHAMFER * s
        val path = android.graphics.Path().apply { moveTo(l + k, t); lineTo(r - k, t); lineTo(r, t + k); lineTo(r, b - k); lineTo(r - k, b); lineTo(l + k, b); lineTo(l, b - k); lineTo(l, t + k); close() }
        p.reset(); p.isAntiAlias = true; ink(Tok.INK, DASH_PANEL)
        c.drawPath(path, p)
        ink(edge, 1f)
        c.drawRect(l + k, t, r - k, t + Tok.RULE_S * s, p)
        mark(l, t, r, b)
    }

    /** A rev bar: segments growing taller, lit up to [level] (0..1), the last ones in [red]. */
    private fun revBar(c: Canvas, l: Float, t: Float, level: Float, red: Int) {
        val segW = DASH_SEG_W * s
        val gap = DASH_SEG_GAP * s
        val n = DASH_SEGMENTS
        val lit = (level.coerceIn(0f, 1f) * n).toInt()
        p.reset()
        for (i in 0 until n) {
            val hgt = (Tok.SP_S + i * DASH_SEG_GROW) * s
            val bottom = t + (Tok.SP_S + n * DASH_SEG_GROW) * s
            ink(if (i >= lit) Tok.WHITE else if (i >= n - DASH_RED_SEGMENTS) red else Tok.WHITE, if (i >= lit) Tok.A_LOW else 1f)
            c.drawRect(l + i * (segW + gap), bottom - hgt, l + i * (segW + gap) + segW, bottom, p)
        }
    }

    /** A rev counter's arc, lit to [q] of the way (the last fifth in [red]), its needle at [needle]. */
    private fun tach(c: Canvas, q: Float, cy: Float, r: Float, red: Int, needle: Float, cx: Float = W / 2) {
        p.reset(); p.isAntiAlias = true
        for (i in 0 until TACH_TICKS) {
            val f = i / (TACH_TICKS - 1f)
            val ang = Math.toRadians((TACH_FROM + TACH_SWEEP * f).toDouble())
            val long = i % TACH_MAJOR == 0
            val r0 = r - (if (long) Tok.SP_XL + Tok.SP_S else Tok.SP_M + Tok.SP_XS / 2) * s
            p.strokeWidth = (if (long) Tok.RULE_L - 1 else Tok.RULE_S) * s
            ink(if (f > q) Tok.WHITE else if (f > TACH_RED_FROM) red else Tok.WHITE, if (f > q) Tok.A_LOW else 1f)
            c.drawLine(cx + r0 * kotlin.math.cos(ang).toFloat(), cy + r0 * sin(ang).toFloat(), cx + r * kotlin.math.cos(ang).toFloat(), cy + r * sin(ang).toFloat(), p)
        }
        val na = Math.toRadians((TACH_FROM + TACH_SWEEP * needle).toDouble())
        ink(red, 1f); p.strokeWidth = Tok.RULE_M * s; p.strokeCap = Paint.Cap.ROUND
        val len = r - (Tok.SP_XL + Tok.SP_S + Tok.SP_XS) * s
        c.drawLine(cx, cy, cx + len * kotlin.math.cos(na).toFloat(), cy + len * sin(na).toFloat(), p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, Tok.SP_S * s, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    /** Speedtrap's captions: capitals on a dash panel, the word being said in red. They snap on like a readout. */
    private fun dashCaption(c: Canvas, sp: Spoken, t: Float, ay: Float, k: Cap) {
        val a = sp.active(t)
        val words = sp.words.map { it.uppercase() }
        val padX = Tok.SP_L * s
        val padY = Tok.SP_M * s
        type(k.face ?: orbitron, Tok.BODY, Tok.WHITE, track = DASH_TRACK)
        val rows = window(wrapIdx(words, words.indices, W * Tok.CAPTION_W - 2 * padX), a)
        val lh = Tok.BODY * s * Tok.LEADING
        capSize = Tok.BODY; capLh = lh
        val widest = rows.maxOf { p.measureText(words.slice(it).joinToString(" ")) }
        val top = ay + lh / 2 - rows.size * lh - padY
        val left = W / 2 - widest / 2 - padX
        fade *= Tok.out(Tok.prog(t - sp.start, Tok.T_MICRO))
        panel(c, left, top, left + widest + 2 * padX, ay + lh / 2 + padY, accent(k.vibe))
        type(k.face ?: orbitron, Tok.BODY, Tok.WHITE, track = DASH_TRACK)
        rows.forEachIndexed { ri, r ->
            row(c, words, r, left + padX, top + padY + lh / 2 + ri * lh, lh, Paint.Align.LEFT, mark = false) { i -> wordInk(k, i, a, k.ink ?: Tok.WHITE, Tok.DIM) }
        }
    }

    /** Speedtrap's titles: flush left in Orbitron, a red tag above; each row boots on after the last. */
    private fun dashHeadline(c: Canvas, text: String, cx: Float, cy: Float, size: Float, ms: Float?, alpha: Float, above: String?, below: String?, maxW: Float, color: Int?, left: Boolean) {
        val t = ms ?: STILL
        val words = text.trim().split(WS).filter { it.isNotEmpty() }.map { it.uppercase() }
        if (words.isEmpty() || alpha <= 0f) return
        val (z, rows) = rowsAt(orbitron, words, size, maxW, DASH_TRACK)
        val lh = z * s * Tok.LEADING
        val blockH = rows.size * lh
        val tagH = (Tok.LABEL + Tok.SP_S) * s
        val labelTop = cy - blockH / 2 - Tok.SP_M * s - tagH
        val cyy = cy + if (above != null) max(0f, H * Tok.INFO_BOTTOM + Tok.SP_S * s - labelTop) else 0f
        val top = cyy - blockH / 2
        val x = if (left) W * Tok.INFO_X else cx
        val align = if (left) Paint.Align.LEFT else Paint.Align.CENTER
        rows.forEachIndexed { ri, r ->
            val q = Tok.out(Tok.prog(t - ri * Tok.STAGGER, Tok.T_BASE))
            if (q <= 0f) return@forEachIndexed
            type(orbitron, z, Tok.WHITE, align, DASH_TRACK)
            fade = alpha * q
            soft()
            ink(color ?: Tok.WHITE, 1f)
            c.drawText(words.slice(r).joinToString(" "), x - (1 - q) * Tok.SP_M * s, mid(top + lh / 2 + ri * lh), p)
        }
        fade = alpha * Tok.out(Tok.prog(t, Tok.T_BASE))
        above?.let { label ->
            type(orbitron, Tok.LABEL, Tok.WHITE, Paint.Align.LEFT, Tok.TRACK_CAPS)
            val tw = p.measureText(label.uppercase())
            val l = if (left) x else x - tw / 2 - Tok.SP_M * s
            val tt = top - Tok.SP_M * s - tagH
            panel(c, l, tt, l + tw + 2 * Tok.SP_M * s, tt + tagH, accent(Vibe.SPEEDTRAP))
            p.reset(); type(orbitron, Tok.LABEL, Tok.WHITE, Paint.Align.LEFT, Tok.TRACK_CAPS); ink(Tok.WHITE, 1f)
            c.drawText(label.uppercase(), l + Tok.SP_M * s, mid(tt + tagH / 2 + Tok.RULE_S * s / 2), p)
        }
        below?.let { smallLabel(c, Vibe.SPEEDTRAP, it, if (left) x else cx, top + blockH + Tok.SP_L * s, fade, align) }
        fade = 1f
    }

    /** Speedtrap's stats: a trip computer — "TRIP A", the title, top speed in a rev counter, the rest in panels. */
    private fun dashStats(c: Canvas, card: RideCard, t: Float, red: Int) {
        val x = W * Tok.INFO_X
        fade = Tok.out(Tok.prog(t, Tok.T_BASE))
        type(orbitron, Tok.SUB, Tok.WHITE, track = Tok.TRACK_CAPS); ink(Tok.WHITE, Tok.DIM)
        c.drawText("TRIP A", x, mid(H * DASH_TRIP_Y), p)
        dashHeadline(c, card.title, x, H * DASH_TITLE_Y, Tok.SUB, t, 1f, null, null, W * Tok.TITLE_W, null, left = true)
        val hero = card.stats.indexOfFirst { "top" in it.second.lowercase() }.takeIf { it >= 0 } ?: 0
        val (value, unit) = card.stats.getOrNull(hero) ?: return
        val q = Tok.out(Tok.prog(t, Tok.T_SLOW))
        tach(c, DASH_STATS_TACH * q, H * DASH_STATS_Y, W * DASH_STATS_R, red, DASH_STATS_TACH * q)
        fade = 1f
        type(orbitron, Tok.HERO, Tok.WHITE, Paint.Align.CENTER); ink(Tok.WHITE, 1f)
        c.drawText(countUp(value, q), W / 2, mid(H * DASH_STATS_Y), p)
        type(orbitron, Tok.LABEL_L, red, Paint.Align.CENTER, Tok.TRACK_CAPS); ink(red, 1f)
        c.drawText(unit.uppercase(), W / 2, mid(H * DASH_STATS_Y + Tok.TITLE * s), p)
        val rest = card.stats.take(4).filterIndexed { i, _ -> i != hero }
        val colW = W * Tok.TITLE_W / rest.size.coerceAtLeast(1)
        rest.forEachIndexed { i, (v, u) ->
            val l = x + i * colW
            val top = H * DASH_PANELS_Y
            fade = Tok.out(Tok.prog(t - (i + 1) * Tok.STAGGER * 2, Tok.T_BASE))
            panel(c, l + Tok.SP_XS * s, top, l + colW - Tok.SP_XS * s, top + DASH_PANEL_H * s, red)
            type(orbitron, Tok.CAP, Tok.WHITE); ink(Tok.WHITE, 1f)
            c.drawText(countUp(v, Tok.out(Tok.prog(t - (i + 1) * Tok.STAGGER * 2, Tok.T_SLOW))), l + Tok.SP_L * s, top + (Tok.SP_M + Tok.CAP) * s, p)
            type(orbitron, Tok.LABEL, Tok.WHITE, track = Tok.TRACK_CAPS); ink(Tok.WHITE, Tok.DIM)
            c.drawText(u.uppercase(), l + Tok.SP_L * s, top + DASH_PANEL_H * s - Tok.SP_M * s, p)
        }
        fade = 1f
    }

    private data class Quad(val face: Typeface, val size: Float, val ink: Int, val a: Float)

    private fun mapBackground(c: Canvas) {
        p.reset(); p.color = Tok.INK; c.drawRect(0f, 0f, W, H, p)
        p.color = Tok.alpha(Tok.WHITE, GRID_A); p.strokeWidth = s
        for (i in 0 until GRID_N) {
            val q = i / (GRID_N - 1f)
            c.drawLine(q * W, 0f, q * W + W * GRID_SLANT, H, p)
            c.drawLine(0f, q * H, W, q * H - H * GRID_SLANT / 2, p)
        }
    }

    /** The route in speed colours; returns where its last drawn point is. */
    private fun drawRoute(c: Canvas, route: List<Triple<Float, Float, Float>>, upTo: Int, zoom: Float, width: Float, pad: Float): Pair<Float, Float>? {
        if (route.size < 2) return null
        val sc = min(W, H) * (0.5f - pad) * zoom
        fun px(i: Int) = W / 2 + route[i].first * sc
        fun py(i: Int) = H / 2 + route[i].second * sc
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND
        val n = min(upTo, route.size)
        for (i in 1 until n) {
            p.color = heat(route[i].third)
            c.drawLine(px(i - 1), py(i - 1), px(i), py(i), p)
        }
        p.style = Paint.Style.FILL
        return px(n - 1) to py(n - 1)
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Sets the paint for text: [size] in s units. */
    private fun type(face: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, track: Float = 0f) {
        p.reset(); p.isAntiAlias = true; p.typeface = face; p.textSize = size * s; p.color = color; p.textAlign = align; p.letterSpacing = track
    }

    /** [color] at [a] of its alpha, times the current [fade]. */
    private fun ink(color: Int, a: Float) {
        p.color = color
        p.alpha = (Color.alpha(color) * a * fade).toInt().coerceIn(0, 255)
    }

    /** The soft shadow for text on video. */
    private fun soft() = p.setShadowLayer(Tok.SOFT_BLUR * s, 0f, Tok.SOFT_Y * s, Tok.alpha(Color.BLACK, Tok.A_MID * fade))

    /** Dark or white words, whichever reads on [bg]. */
    private fun inkOn(bg: Int): Int = if (Color.luminance(bg) > 0.5f) Tok.INK else Tok.WHITE

    /** Whether [c] reads on [bg]. */
    private fun readable(c: Int, bg: Int) = abs(Color.luminance(c) - Color.luminance(bg)) > READABLE

    /** The baseline that centres the current font on [y]. */
    private fun mid(y: Float) = y - (p.ascent() + p.descent()) / 2

    private fun mark(l: Float, t: Float, r: Float, b: Float) {
        if (blk.isEmpty) blk.set(l, t, r, b) else blk.union(l, t, r, b)
    }

    /** Words [r] in a row centred on [cx] (or starting at it, [align] LEFT), [style] colouring each before it's drawn. */
    private fun row(c: Canvas, words: List<String>, r: IntRange, cx: Float, y: Float, lh: Float, align: Paint.Align = Paint.Align.CENTER, mark: Boolean = true, style: (Int) -> Unit) {
        p.textAlign = Paint.Align.LEFT
        val space = p.measureText(" ")
        val total = p.measureText(words.slice(r).joinToString(" "))
        var x = if (align == Paint.Align.CENTER) cx - total / 2 else cx
        if (mark) mark(x, y - lh / 2, x + total, y + lh / 2)
        val base = mid(y)
        for (i in r) {
            style(i)
            c.drawText(words[i], x, base, p)
            x += p.measureText(words[i]) + space
        }
    }

    /** The largest scale size at or below [size] at which [text] fits [maxW] px (stepping down the scale), not below [floor]. */
    private fun fit(face: Typeface, text: String, size: Float, maxW: Float, floor: Float): Float {
        m.typeface = face; m.letterSpacing = 0f
        var z = size
        while (z > floor) {
            m.textSize = z * s
            if (m.measureText(text) <= maxW) return z
            z = Tok.down(z)
        }
        return floor
    }

    /** The size and rows for display type: three rows at most, stepping down the scale until it fits. */
    private fun rowsAt(face: Typeface, words: List<String>, size: Float, maxW: Float, track: Float): Pair<Float, List<IntRange>> {
        var z = size
        while (true) {
            p.reset(); p.typeface = face; p.textSize = z * s; p.letterSpacing = track
            val rows = wrapIdx(words, words.indices, maxW)
            val fits = rows.size <= MAX_ROWS && rows.all { p.measureText(words.slice(it).joinToString(" ")) <= maxW }
            if (fits || z <= Tok.CAP_S) return z to rows
            z = Tok.down(z)
        }
    }

    /** Words [range] in rows no wider than [maxW] (current paint), never leaving one word alone on the last row. */
    private fun wrapIdx(words: List<String>, range: IntRange, maxW: Float): List<IntRange> {
        val rows = ArrayList<IntRange>()
        var from = range.first
        for (i in range) {
            if (i > from && p.measureText(words.subList(from, i + 1).joinToString(" ")) > maxW) {
                rows += from until i
                from = i
            }
        }
        if (range.isEmpty()) return rows
        rows += from..range.last
        // An orphan: bring one word down from the row above when it still fits.
        if (rows.size >= 2) {
            val last = rows.last()
            val prev = rows[rows.size - 2]
            if (last.first == last.last && prev.last - prev.first >= 2 && p.measureText(words.subList(prev.last, last.last + 1).joinToString(" ")) <= maxW) {
                rows[rows.size - 2] = prev.first until prev.last
                rows[rows.size - 1] = prev.last..last.last
            }
        }
        return rows
    }

    /** Up to [n] words at a time, breaking at punctuation, with no group of one word when it can be helped. */
    private fun chunks(words: List<String>, n: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        for (i in words.indices) {
            if (i - from + 1 == n || words[i].last() in PUNCT) { out += from..i; from = i + 1 }
        }
        if (from <= words.lastIndex) out += from..words.lastIndex
        var i = 0
        while (out.size > 1 && i < out.size) {
            val g = out[i]
            if (g.first == g.last) {
                val prev = out.getOrNull(i - 1)
                when {
                    // Take a word from a longer group before it.
                    prev != null && prev.last - prev.first >= 2 && words[prev.last].last() !in PUNCT -> { out[i - 1] = prev.first until prev.last; out[i] = prev.last..g.last }
                    prev != null -> { out[i - 1] = prev.first..g.last; out.removeAt(i); continue }
                    else -> { val next = out[1]; out[0] = g.first..next.last; out.removeAt(1); continue }
                }
            }
            i++
        }
        return out
    }

    /** The two rows holding word [active]: long lines page through two rows at a time, nothing skipped. */
    private fun window(rows: List<IntRange>, active: Int): List<IntRange> {
        if (rows.size <= 2) return rows
        val r = rows.indexOfFirst { active in it }.coerceAtLeast(0)
        val from = r / 2 * 2
        return rows.subList(from, min(rows.size, from + 2))
    }

    /** "3.6" counting up from 0 with the same decimals. */
    private fun countUp(value: String, q: Float): String {
        val v = value.toDoubleOrNull() ?: return value
        val dp = value.substringAfter('.', "").length.takeIf { '.' in value } ?: 0
        return String.format(java.util.Locale.US, "%.${dp}f", v * q)
    }

    /** Smooth noise in -1..1 (a few slow sines, so it drifts rather than jitters). */
    private fun noise(x: Float, seed: Int): Float =
        (sin(x + seed * 1.7f) + 0.5f * sin(2.3f * x + seed * 0.9f) + 0.25f * sin(4.1f * x + seed * 2.3f)) / 1.75f

    private companion object {
        val WS = Regex("\\s+")
        const val PUNCT = ",.!?;:"
        const val SECTION_ID = "section"

        fun cl(x: Float) = x.coerceIn(0f, 1f)

        /** Teal-blue when slow, through amber to red at the ride's top speed. */
        fun heat(f: Float): Int {
            val t = f.coerceIn(0f, 1f)
            fun lerp(a: Int, b: Int, k: Float) = Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
            return if (t < 0.5f) lerp(Tok.BLUE, Tok.AMBER, t * 2) else lerp(Tok.AMBER, Tok.RED, (t - 0.5f) * 2)
        }

        /** A time long after every entrance (a still). */
        const val STILL = 1e7f
        const val FRAME_MS = 1000f / 30f
        const val MAX_ROWS = 3

        // Camera: zoom amounts and shifts (fractions of the frame).
        const val KICK = 0.035f
        const val KICK_SPAN = 5f
        const val ZOOM_K = 0.45f
        const val WHIP_X = 0.75f
        const val GLITCH_X = 0.03f
        const val SLASH_K = 0.3f
        const val SOFT_K = 0.05f
        // Gear shift: the squat and kick, the shake, and the flash at the shift.
        const val GEAR_K = 0.06f
        const val GEAR_Y = 0.012f
        const val GEAR_DEG = 1.6f
        const val GEAR_SHAKE_MS = 70f
        const val GEAR_FLASH = 0.22f
        // Throttle blip: how far each rev zooms.
        const val BLIP_K = 0.07f
        // Visor drop: how far down it comes (past the bottom, so the cut's hidden), its curve and look.
        const val VISOR_REACH = 1.1f
        const val VISOR_CURVE = 0.045f
        const val VISOR_TOP_A = 0.88f
        const val VISOR_BOTTOM_A = 0.96f
        const val VISOR_TINT = 0xFF14202C.toInt()
        const val VISOR_RIM = 10f
        const val VISOR_SHINE_A = 0.35f
        const val VISOR_GLARE_A = 0.12f
        // Mirror: its size and place at the cut, how much bigger it starts, its rim and stem.
        const val MIRROR_W = 0.86f
        const val MIRROR_H = 0.5f
        const val MIRROR_Y = 0.42f
        const val MIRROR_GROW = 4f
        const val MIRROR_DARK_IN = 3f
        const val MIRROR_RIM = 9f
        const val MIRROR_STEM_W = 14f
        const val MIRROR_CHROME = 0xFF8E959C.toInt()
        const val MIRROR_STEM = 0xFF1A1C1F.toInt()
        // Exhaust: how many puffs, where they come from, their size and greys.
        const val SMOKE_PUFFS = 16
        const val SMOKE_FROM_X = 0.1f
        const val SMOKE_FROM_Y = 0.95f
        const val SMOKE_MIN = 0.18f
        const val SMOKE_SPREAD = 0.22f
        const val SMOKE_A = 0.9f
        const val SMOKE_HAZE = 0.85f
        const val SMOKE_LIGHT = 0xFFB4B9BE.toInt()
        const val SMOKE_DARK = 0xFF7C8288.toInt()
        const val THROUGH_K = 0.06f
        const val PAPER_X = 0.06f
        const val WEAVE = 0.003f
        const val WEAVE_MS = 900f
        const val GATE_JUMP = 0.008f
        const val FLICKER_MS = 140f

        // The redline light: the whole frame at most this strong; or the edge frame and its soft inside (s units).
        const val REDLINE_FULL = 0.30f
        const val REDLINE_EDGE = 7f
        const val REDLINE_SOFT = 4f

        // Transition graphics.
        const val FLASH_A = 0.8f
        const val DIP_HOLD = 0.8f
        const val LEAK_MIN = 0.2f
        const val LEAK_REACH = 1.0f
        const val LEAK_MID = 0.45f
        const val GLITCH_MIN = 0.15f
        const val GLITCH_STEPS = 40f
        const val GLITCH_BANDS = 7

        // Texture.
        const val VIGNETTE_R = 0.78f
        const val VIGNETTE_CLEAR = 0.32f
        const val NOISE_PX = 128
        const val NOISE_SEED = 11L
        const val LETTERBOX = 0.09f

        // Captions and type motion.
        const val EN_SCALE = 0.6f
        const val POSTER_FROM = 1.08f
        const val POSTER_WAIT = 0.4f
        const val POSTER_TRACK = -0.02f
        const val POSTER_LEADING = 0.98f
        /** Bold's one big number on the stats (beyond the type scale on purpose: it's the whole card). */
        const val POSTER_NUMBER = 230f

        // Speedtrap's dash (s units, fractions of the frame for places).
        const val DASH_CHAMFER = 10f
        const val DASH_PANEL = 0.82f
        const val DASH_TRACK = 0.04f
        const val DASH_BADGE_H = 60f
        const val DASH_SEGMENTS = 10
        const val DASH_RED_SEGMENTS = 3
        const val DASH_SEG_W = 7f
        const val DASH_SEG_GAP = 2f
        const val DASH_SEG_GROW = 1.2f
        const val TACH_TICKS = 40
        const val TACH_MAJOR = 5
        const val TACH_FROM = 150f
        const val TACH_SWEEP = 240f
        const val TACH_RED_FROM = 0.8f
        const val TACH_Y = 0.45f
        const val TACH_R = 0.38f
        const val DASH_TRIP_Y = 0.14f
        const val DASH_TITLE_Y = 0.175f
        const val DASH_STATS_Y = 0.46f
        const val DASH_STATS_R = 0.32f
        const val DASH_STATS_TACH = 0.92f
        const val DASH_PANELS_Y = 0.63f
        const val DASH_PANEL_H = 80f
        const val POSTER_OUTLINE = 2.5f
        const val POSTER_NUM_Y = 0.42f
        const val POSTER_UNIT_Y = 0.53f
        const val POSTER_ROW_Y = 0.64f
        const val POP_FROM = 0.85f
        const val PLAYFUL_TILT = 1.5f
        const val PLAYFUL_TITLE_TILT = -2f
        const val PLAYFUL_BADGE_TILT = -4f
        const val CINE_TRACK = 0.08f
        const val STRIP_NUDGE = 6f
        const val STRIP_TILT = 0.5f
        const val STRIP_SEED = 31L
        const val STRIP_UNDER = 0.6f
        const val OUTLINE = 0.08f
        const val READABLE = 0.3f
        const val DISTINCT = 80

        // Stickers and badge (s units unless noted).
        const val STICKER_FROM = 0.9f
        const val DISC = 92f
        const val BADGE_DISC = 48f
        const val EMOJI = 110f
        const val RING = 120f
        const val PULSE = 0.03f
        const val ARC_FROM = 135f
        const val ARC_SWEEP = 270f
        const val ARC_TOP_KMH = 160f
        const val LEAN_MAX = 60
        const val LEAN_FOOT = 0.43f
        const val LEAN_LABEL = 0.78f

        // Title, cover and stats (fractions of the frame unless noted).
        const val TITLE_MID = 0.46f
        const val COVER_Y = 0.64f
        const val COVER_SHADE = 0.2f
        const val ROUTE_DRAW = 0.85f
        const val ROUTE_DY = 0.1f
        const val ROUTE_ZOOM = 1.05f
        const val ROUTE_ZOOM_IN = 0.1f
        const val ROUTE_PAD = 0.12f
        const val GLOW = 60f
        const val GRID_A = 0.05f
        const val GRID_N = 14
        const val GRID_SLANT = 0.15f
        const val STATS_ROUTE_DY = 0.04f
        const val STATS_LEFT = 0.27f
        const val STATS_RIGHT = 0.73f
        const val STATS_ROW_1 = 0.40f
        const val STATS_ROW_2 = 0.58f
        const val TILE_W = 0.21f
        const val TILE_H = 0.075f
        const val LOGO = 90f
        const val LOGO_Y = 0.70f
        const val MARK_Y = 0.76f
    }
}
