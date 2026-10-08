package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionKind
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.CaptionLook
import com.ridetrack.app.studio.ClipColor
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StickerKind
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.StudioStyle
import com.ridetrack.app.studio.StyleJson
import com.ridetrack.app.studio.StylePart
import com.ridetrack.app.studio.Styles
import com.ridetrack.app.studio.TextLook
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.studio.Transition
import com.ridetrack.app.studio.TransitionKind
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StylesTest {
    private fun bit(id: String, clipDur: Long = 20_000) = Bit("$id#0", id, clipDur, 0, clipDur, 1_000_000, emptyList(), 40.0, 3f)

    /** Section 0: a (talking) + b; section 1: c (riding). */
    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 0, 4_000, listOf(CaptionLine(500, 1_500, "Arre yaar")), hook = true, section = 0)
        val b = ClipSegment(bit("b"), 0, 3_000, emptyList(), hook = false, section = 0)
        val c = ClipSegment(bit("c"), 0, 4_000, emptyList(), hook = false, section = 1)
        return TimelineEdits.addText(StudioPlan(listOf(a, b, c, StatsSegment(1_500)), Vibe.VLOG), 500, "Sixty five", "t1")
    }

    private val neon = StudioStyle(
        "n1", "Night neon", Vibe.HYPE, about = "neon, punchy",
        captions = CaptionLook(CaptionKind.PLAIN, 1.3f, 0.8f, true), textLook = TextLook.OUTLINE, textColor = 0xFFFF00FF.toInt(),
        transition = Transition(TransitionKind.GLITCH), pace = 0.6f, punchIn = true, color = ClipColor(saturation = 0.4f, warmth = -0.3f),
        speedBadge = false, speedSticker = true, intro = true, duck = false,
    )

    @Test
    fun `the new freedoms are saved and read back, and older files still read`() {
        val st = StudioStyle(
            "st-x", "Neon", Vibe.HYPE,
            captions = CaptionLook(CaptionKind.PLAIN, 1.6f, 0.4f, true, color = 0xFF60A5FA.toInt(), highlight = 0xFFFF453A.toInt(), font = com.ridetrack.app.studio.FontChoice.SERIF, box = true),
            textFont = com.ridetrack.app.studio.FontChoice.HAND, textSize = 1.3f, textY = 0.2f,
            transition = Transition(TransitionKind.WHIP, customMs = 450),
        )
        assertEquals(st, StyleJson.read(StyleJson.write(st)))
        // A caption look saved before these existed.
        val old = com.ridetrack.app.studio.LookJson.caption(org.json.JSONObject("""{"kind":"PUNCH","size":1.2,"y":null,"karaoke":false}"""))
        assertEquals(CaptionLook(CaptionKind.PUNCH, 1.2f, null, false), old)
        assertEquals(450L, Transition(TransitionKind.FADE, customMs = 450).ms(Vibe.HYPE))
    }

    @Test
    fun `a style's text font, size and height go onto the edit's text`() {
        val withText = TimelineEdits.addText(plan(), 0, "3 hours. No water.", "t1")
        val st = StudioStyle("st-x", "X", Vibe.HYPE, textFont = com.ridetrack.app.studio.FontChoice.BOLD, textSize = 1.5f, textY = 0.15f)
        val t = Styles.apply(withText, st) { "n" }.texts.first { it.id == "t1" }
        assertEquals(com.ridetrack.app.studio.FontChoice.BOLD, t.font)
        assertEquals(1.5f, t.size)
        assertEquals(0.15f, t.y)
    }

    @Test
    fun `a style sets the look, captions, text, colour, camera and stickers`() {
        var n = 0
        val p = Styles.apply(plan(), neon) { "s${n++}" }
        assertEquals(Vibe.HYPE, p.vibe)
        assertEquals(CaptionKind.PLAIN, p.captionLook.kind)
        assertEquals(TextLook.OUTLINE, p.texts.single().look)
        assertTrue(p.clips.all { it.color.saturation == 0.4f })
        assertTrue(p.clips[0].frame.isNotEmpty(), "punch-in on the talking clip")
        assertEquals(listOf(StickerKind.SPEED), p.stickers.map { it.kind })
        assertEquals(false, p.mix.duck)
        val o = Styles.options(StudioOptions(), neon)
        assertEquals(false, o.speedBadge)
        assertEquals(true, o.intro)
    }

    @Test
    fun `transitions go between sections, or on every cut`() {
        val p = Styles.apply(plan(), neon) { "s" }
        // b is in a's section: a plain cut; c starts a new section: the style's transition.
        assertNull(p.clips[1].transition)
        assertEquals(TransitionKind.GLITCH, p.clips[2].transition?.kind)
        val every = Styles.apply(plan(), neon.copy(everyCut = true)) { "s" }
        assertEquals(TransitionKind.GLITCH, every.clips[1].transition?.kind)
    }

    @Test
    fun `pace makes riding shots shorter, never the talking ones`() {
        val p = Styles.apply(plan(), neon) { "s" }
        assertEquals(4_000, p.clips[0].durMs)
        assertEquals(1_800, p.clips[1].durMs)
        val slow = Styles.apply(plan(), neon.copy(pace = 1.5f)) { "s" }
        assertEquals(4_500, slow.clips[1].durMs)
    }

    @Test
    fun `a Reel's look becomes a style that makes the same look`() {
        var n = 0
        val styled = Styles.apply(plan(), neon.copy(everyCut = true)) { "s${n++}" }
        val o = Styles.options(StudioOptions(), neon)
        val back = Styles.fromReel(styled, o, "r1", "From my Reel")
        assertEquals(Vibe.HYPE, back.base)
        assertEquals(neon.captions, back.captions)
        assertEquals(TransitionKind.GLITCH, back.transition.kind)
        assertTrue(back.everyCut)
        assertEquals(neon.color, back.color)
        assertTrue(back.punchIn && back.speedSticker && !back.speedBadge)
    }

    @Test
    fun `shuffle varies one part, reset puts a part back`() {
        val v = Styles.shuffle(neon, 3)
        assertNotEquals(neon, v)
        val r = Styles.reset(neon.copy(pace = 1.4f, captions = CaptionLook()), neon, StylePart.PACE)
        assertEquals(0.6f, r.pace)
        assertEquals(CaptionLook(), r.captions)
    }

    @Test
    fun `styles are saved, shared as a code, and made from Gemini's answer`() {
        assertEquals(neon, StyleJson.read(StyleJson.write(neon)))
        assertEquals(listOf(neon), StyleJson.readAll(StyleJson.writeAll(listOf(neon))))
        val code = StyleJson.code(neon)
        assertEquals(neon.copy(id = "x"), StyleJson.fromCode("Try my style: $code !", "x"))
        val g = StyleJson.fromGemini("""Here: {"name":"Rain","base":"cinematic","captions":"serif","transition":"fade","pace":1.3}""", "g1")!!
        assertEquals(Vibe.CINE, g.base)
        assertEquals(CaptionKind.SERIF, g.captions.kind)
        assertEquals(TransitionKind.FADE, g.transition.kind)
        assertEquals(1.3f, g.pace)
    }
}
