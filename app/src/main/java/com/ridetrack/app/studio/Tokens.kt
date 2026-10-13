package com.ridetrack.app.studio

import kotlin.math.pow

/**
 * Keppo Studio's design tokens: every size, colour, shadow, radius, duration, easing and place
 * the video's graphics use. Sizes are in s units (a 540-wide frame; ×2 at 1080). Drawing code
 * reads these and nothing else.
 */
object Tok {
    // ---- type scale (s units, about ×1.25 a step): these nine only ----------------------------
    const val LABEL = 14f
    const val LABEL_L = 18f
    const val SUB = 22f
    const val CAP_S = 28f
    const val BODY = 36f
    const val CAP = 44f
    const val CAP_L = 56f
    const val TITLE = 72f
    const val HERO = 92f
    val SCALE = floatArrayOf(LABEL, LABEL_L, SUB, CAP_S, BODY, CAP, CAP_L, TITLE, HERO)

    /** The next size down the scale (the smallest stays). */
    fun down(size: Float): Float = SCALE.lastOrNull { it < size - 0.01f } ?: SCALE.first()

    /** The largest scale size at or below [size]. */
    fun snap(size: Float): Float = SCALE.lastOrNull { it <= size + 0.01f } ?: SCALE.first()

    /** Captions never shrink below this to fit. */
    const val CAPTION_FLOOR = CAP_S

    /** Tracking for small caps labels, and the tight tracking of large Clean type. */
    const val TRACK_CAPS = 0.2f
    const val TRACK_CAPS_EDITORIAL = 0.15f
    const val TRACK_TIGHT = -0.01f

    // ---- colour ----------------------------------------------------------------------------------
    // Plain ARGB ints (no Android calls), so the pure parts of Studio can use them in tests.
    const val INK = 0xFF0B0B0D.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val PAPER = 0xFFFFF4E6.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val BLUE = 0xFF4F8FD8.toInt()
    const val CORAL = 0xFFFF6B4A.toInt()
    const val AMBER = 0xFFFFA43A.toInt()
    const val RED = 0xFFE5322D.toInt()

    /** The rider's colour choices: one curated palette (every vibe's accent is in it). */
    val SWATCHES: List<Int> = listOf(WHITE, INK, PAPER, YELLOW, CORAL, BLUE, RED, AMBER)

    /** Scrims and shadows: these three alphas only. */
    const val A_LOW = 0.25f
    const val A_MID = 0.45f
    const val A_HIGH = 0.65f

    /** Text alphas: the words not being said (Clean; the rest), and quiet labels. */
    const val DIM_CLEAN = 0.55f
    const val DIM = 0.6f
    const val QUIET = 0.85f

    /** [color] at [a] (0..1) of full opacity. */
    fun alpha(color: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xFFFFFF)

    // ---- spacing (s units) and line height --------------------------------------------------------
    const val SP_XS = 4f
    const val SP_S = 8f
    const val SP_M = 12f
    const val SP_L = 16f
    const val SP_XL = 20f
    /** Strip captions' side padding (with [SP_S] above and below). */
    const val SP_STRIP = 14f
    /** Line height as a multiple of the type size: tight for display type, normal for text. */
    const val LEADING_TIGHT = 1.08f
    const val LEADING = 1.25f
    /** Rules and underlines. */
    const val RULE_S = 3f
    const val RULE_M = 4f
    const val RULE_L = 6f
    /** A line's rise as it fades in (Cinematic captions). */
    const val RISE = 10f

    // ---- shadows ---------------------------------------------------------------------------------
    /** Text legibility on video: blur 12s, 2s down, black at [A_MID]. */
    const val SOFT_BLUR = 12f
    const val SOFT_Y = 2f
    /** A hard ink copy behind the type, (4s, 5s): Bold only. */
    const val HARD_X = 4f
    const val HARD_Y = 5f

    /** A layer's frame: 2s white at 0.8. */
    const val BORDER = 2f
    const val BORDER_A = 0.8f

    // ---- radii -----------------------------------------------------------------------------------
    const val R_SM = 8f
    const val R_MD = 16f
    const val R_PILL = 999f

    // ---- motion ----------------------------------------------------------------------------------
    const val T_MICRO = 120L
    const val T_BASE = 240L
    const val T_EMPH = 400L
    const val T_SLOW = 700L
    /** Lines and rows come in one after another this far apart. */
    const val STAGGER = 60L
    /** A highlight lands this long before its word is said. */
    const val LEAD = 60L

    /** Exits are quicker than entrances. */
    fun exit(ms: Long): Long = ms * 3 / 4

    /** Quint out: every entrance. */
    fun out(t: Float): Float = 1 - (1 - t.coerceIn(0f, 1f)).pow(5)
    /** Cubic in: every exit. */
    fun inn(t: Float): Float = t.coerceIn(0f, 1f).pow(3)
    /** Cubic in-out: camera moves and wipes. */
    fun inOut(t: Float): Float { val x = t.coerceIn(0f, 1f); return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2 }
    /** Back-out with a small overshoot: Playful pops and Clean captions, sparingly. */
    fun spring(t: Float): Float = back(t, 1.2f)
    /** Back-out with a firm overshoot: Bold only. */
    fun springHard(t: Float): Float = back(t, 1.7f)
    private fun back(t: Float, c: Float): Float { val x = t.coerceIn(0f, 1f) - 1; return 1 + (c + 1) * x * x * x + c * x * x }

    /** 0..1 progress of [ms] over [dur] (linear, clamped). */
    fun prog(ms: Float, dur: Long): Float = (ms / dur.coerceAtLeast(1)).coerceIn(0f, 1f)

    // ---- layout slots (fractions of H, unless noted) --------------------------------------------
    /** Nothing above this line (status bar, the app's top bar). */
    const val TOP_SAFE = 0.12f
    /** Opening hook, section text and timeline text sit around here. */
    const val TITLE_Y = 0.22f
    /** The speed badge: top-left. */
    const val INFO_X = 0.06f
    const val INFO_Y = 0.14f
    /** The info slot ends here: titles' labels start below it. */
    const val INFO_BOTTOM = 0.17f
    /** Captions: the last row's centre; more rows grow upward. */
    const val CAPTION_Y = 0.66f
    /** Nothing below this line (the app's caption and buttons). */
    const val BOTTOM_SAFE = 0.80f
    /** The app's buttons on the right: nothing at x > 0.86W between 0.45H and 0.80H. */
    const val RAIL_X = 0.86f
    const val RAIL_TOP = 0.45f
    const val RAIL_BOTTOM = 0.80f
    /** Captions are at most this wide (of W), centred, so they stay clear of the right rail. */
    const val CAPTION_W = 0.72f
    /** Titles and other centred text are at most this wide (of W). */
    const val TITLE_W = 0.84f

    /**
     * Where a rider-placed item ([x], [y] its centre, fractions of the frame) is drawn: kept in the
     * safe area, and left of the right rail where the rail is.
     */
    fun place(x: Float, y: Float): Pair<Float, Float> {
        val yy = y.coerceIn(TOP_SAFE + 0.02f, BOTTOM_SAFE - 0.02f)
        val xx = if (yy in RAIL_TOP..RAIL_BOTTOM) x.coerceIn(0.06f, RAIL_X - 0.08f) else x.coerceIn(0.06f, 0.94f)
        return xx to yy
    }

    // ---- holds -----------------------------------------------------------------------------------
    /** The opening text is gone by this time (or earlier when the hook clip is shorter). */
    const val OPENER_MS = 2_800L
    /** A section's on-screen text stays this long. */
    const val SECTION_TEXT_MS = 2_600L
    /** A caption line stays up this long after it's said (unless the next one starts). */
    const val LINE_HOLD_MS = 800L
    /** Type captions: a character every this long (a line is all in by [T_EMPH] at most). */
    const val TYPE_CHAR_MS = 25L
    /** Overlay animation in Editorial runs at 12 frames a second (stop-motion). */
    const val STOP_MOTION_MS = 1000f / 12f

    // ---- texture ---------------------------------------------------------------------------------
    const val VIGNETTE = A_LOW
    const val VIGNETTE_FILM = 0.30f
    const val GRAIN_FILM = 0.07f
    const val GRAIN_CINE = 0.04f
    const val FLICKER = 0.04f
}

/**
 * Each vibe's language: one accent, one easing personality, its default caption style and
 * section transition, the transitions its picker offers, its camera, grain and vignette.
 */
data class VibeSpec(
    /** The vibe's one accent; null = white and ink only. */
    val accent: Int?,
    val caption: CaptionKind,
    /** What plays at a section change ("Style"); CUT = nothing. */
    val section: TransitionKind,
    /** The transitions the picker offers (None first). */
    val picker: List<TransitionKind>,
    /** The camera's slow push across a clip; null = none. */
    val push: Pair<Float, Float>?,
    /** Grain on by default (Film); Cinematic can turn it on. */
    val grain: Boolean,
    /** The vignette's alpha at the frame's edge; 0 = none. */
    val vignette: Float,
    /** The redline light (red pulses while the engine is at the redline): Speedtrap's. */
    val redline: Boolean = false,
)

/** The defaults table. */
val Vibe.spec: VibeSpec
    get() = when (this) {
        Vibe.VLOG -> VibeSpec(Tok.BLUE, CaptionKind.CLEAN, TransitionKind.ZOOM_THROUGH, listOf(TransitionKind.CUT, TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.ZOOM_THROUGH), 1.02f to 1.04f, false, 0f)
        Vibe.CINE -> VibeSpec(Tok.PAPER, CaptionKind.SERIF, TransitionKind.DIP, listOf(TransitionKind.CUT, TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.DIP, TransitionKind.SHUTTER), 1.02f to 1.05f, false, Tok.VIGNETTE)
        Vibe.HYPE -> VibeSpec(Tok.YELLOW, CaptionKind.PUNCH, TransitionKind.SLASH, listOf(TransitionKind.CUT, TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.SLASH, TransitionKind.WHIP), 1.03f to 1.06f, false, Tok.VIGNETTE)
        Vibe.CHILL -> VibeSpec(Tok.CORAL, CaptionKind.LABEL, TransitionKind.SUN, listOf(TransitionKind.CUT, TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.SUN), 1.02f to 1.04f, false, Tok.VIGNETTE)
        Vibe.FILM -> VibeSpec(Tok.AMBER, CaptionKind.TYPE, TransitionKind.LIGHT_LEAK, listOf(TransitionKind.CUT, TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.LIGHT_LEAK, TransitionKind.FLASH), null, true, Tok.VIGNETTE_FILM)
        Vibe.EDITORIAL -> VibeSpec(Tok.RED, CaptionKind.STRIP, TransitionKind.CARD, listOf(TransitionKind.CUT, TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.CARD), 1.02f to 1.04f, false, Tok.VIGNETTE)
        Vibe.NATIVE -> VibeSpec(null, CaptionKind.PLAIN, TransitionKind.CUT, listOf(TransitionKind.CUT, TransitionKind.FADE), null, false, 0f)
    }

/** What a transition the rider set (or the automatic "Style") actually is on [vibe]. */
fun TransitionKind.on(vibe: Vibe): TransitionKind = if (this == TransitionKind.STYLE) vibe.spec.section else this
