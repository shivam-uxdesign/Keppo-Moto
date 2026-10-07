package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.Posting
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostingTest {
    private fun bit(id: String) = Bit("$id#0", id, 10_000, 0, 10_000, 1_000_000, emptyList(), 40.0, 3f)

    private fun plan() = StudioPlan(
        listOf(
            ClipSegment(bit("a"), 0, 4_000, listOf(CaptionLine(0, 1_000, "Bhai paani"), CaptionLine(1_000, 2_000, "nahi piya")), hook = true),
            ClipSegment(bit("b"), 0, 3_000, listOf(CaptionLine(0, 900, "chalo")), hook = false),
        ),
        Vibe.HYPE,
    )

    private fun reel(id: String, title: String, views: Int?, likes: Int? = null) = ReelProject(
        id = id, rideId = "r", createdAt = 1, updatedAt = 1, title = title, series = "", episode = 1, hookLine = "", postCaption = "", story = null,
        options = StudioOptions(), musicUri = null, musicName = null, plan = plan(), takes = emptyList(), tips = emptyList(), durationMs = 7_000, views = views, likes = likes,
    )

    @Test
    fun `what did best goes to Gemini, once there are two to compare`() {
        assertEquals("", Posting.performance(listOf(reel("1", "Rain run", 900))))
        val text = Posting.performance(listOf(reel("1", "Rain run", 900), reel("2", "Flyover", 12_400, 800), reel("3", "No views", null)))
        assertTrue(text.indexOf("Flyover") < text.indexOf("Rain run"), text)
        assertTrue("12400 views, 800 likes" in text && "No views" !in text, text)
    }

    @Test
    fun `subtitles replace each line in its place`() {
        assertEquals(listOf("Bhai paani", "nahi piya", "chalo"), Posting.captionTexts(plan()))
        val p = Posting.withCaptions(plan(), listOf("Bro, water", "didn't drink", "let's go"))
        assertEquals(listOf("Bro, water", "didn't drink", "let's go"), Posting.captionTexts(p))
        assertEquals(listOf("one", "two", "three"), Posting.parseTranslation("""{"lines":["one","two","three"]}""", 3))
        assertNull(Posting.parseTranslation("""{"lines":["one"]}""", 3))
    }

    @Test
    fun `post text and the hook check are read from Gemini`() {
        val post = Posting.parsePost("""ok {"instagram":"Monsoon run 🏍️ #bikelife","youtubeTitle":"Rain on the flyover #shorts","youtubeDescription":"Evening ride"}""")!!
        assertEquals("Rain on the flyover #shorts", post.youtubeTitle)
        val hook = Posting.parseHook("""{"stops":false,"why":"Starts on the road, no face","fix":"Open on the shout"}""")!!
        assertEquals(false, hook.stops)
        assertEquals("Open on the shout", hook.fix)
    }

    @Test
    fun `views, likes and YouTube text are saved with the Reel`() {
        val p = reel("1", "Flyover", 12_400, 800).copy(youtubeTitle = "Rain #shorts", youtubeDescription = "Evening")
        assertEquals(p, ReelJson.read(ReelJson.write(p)))
    }
}
