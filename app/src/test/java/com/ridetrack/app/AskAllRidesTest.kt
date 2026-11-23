package com.ridetrack.app

import com.ridetrack.app.studio.AskClip
import com.ridetrack.app.studio.AskEvent
import com.ridetrack.app.studio.AskFilter
import com.ridetrack.app.studio.AskPatch
import com.ridetrack.app.studio.AskPrompt
import com.ridetrack.app.studio.AskSearch
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.telemetry.model.RideEventType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #12: asking across all rides. */
class AskAllRidesTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun day(s: String) = LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun clip(
        id: String, ride: String, date: String, dur: Long = 20_000, lines: List<CaptionLine> = emptyList(), seen: List<String>? = null,
        downs: List<Long>? = emptyList(), types: Set<RideEventType> = emptySet(), eventAt: Long? = 10_000,
    ) = AskClip(id, "ride-$ride", ride, day(date) + 3_600_000, dur, eventAt, types, lines, seen, downs)

    private val clips = listOf(
        clip("a1", "Nahan", "2026-11-16", downs = listOf(5_000, 12_000), lines = listOf(CaptionLine(1_000, 2_500, "Tooooo!"))),
        clip("a2", "Nahan", "2026-11-16", seen = listOf("rain", "wet road"), types = setOf(RideEventType.HARD_BRAKE)),
        clip("b1", "Murthal", "2026-11-08", downs = listOf(8_000), lines = listOf(CaptionLine(4_000, 6_000, "bhai kya road hai"))),
        clip("c1", "Sohna", "2026-10-12", seen = listOf("rain", "puddle"), downs = null),
        clip("d1", "Neemrana", "2026-09-28", downs = listOf(3_000), lines = listOf(CaptionLine(0, 1_200, "tooooo"))),
    )

    @Test
    fun `downshifts are found in every ride, each a short part around it, in filming order`() {
        val hits = AskSearch.find(AskFilter(events = setOf(AskEvent.DOWNSHIFT)), clips)
        assertEquals(listOf("d1", "b1", "a1", "a1"), hits.map { it.momentId })
        assertEquals(3_000L, hits.last().lengthMs)
        assertEquals(10_500L, hits.last().inMs)
        assertEquals(3, AskSearch.rides(hits))
        assertEquals("4 downshifts", AskSearch.count(AskFilter(events = setOf(AskEvent.DOWNSHIFT)), hits.size))
    }

    @Test
    fun `dates, words said, things seen and the voice narrow it down`() {
        val since = AskFilter(events = setOf(AskEvent.DOWNSHIFT), fromMillis = day("2026-11-01"))
        assertEquals(setOf("a1", "b1"), AskSearch.find(since, clips).map { it.momentId }.toSet())
        // Said: whole words, any spelling the search already allows.
        val said = AskSearch.find(AskFilter(said = listOf("tooooo")), clips)
        assertEquals(setOf("a1", "d1"), said.map { it.momentId }.toSet())
        assertTrue(said.first().why.startsWith("You said"))
        // Seen, and an event in the same clip: both must match.
        assertEquals(listOf("a2"), AskSearch.find(AskFilter(seen = listOf("rain"), events = setOf(AskEvent.HARD_BRAKE)), clips).map { it.momentId })
        assertEquals(setOf("a2", "c1"), AskSearch.find(AskFilter(seen = listOf("rain")), clips).map { it.momentId }.toSet())
        // Only clips where the rider talks.
        assertEquals(setOf("a1", "b1"), AskSearch.find(AskFilter(events = setOf(AskEvent.DOWNSHIFT), voice = true, fromMillis = day("2026-10-01")), clips).map { it.momentId }.toSet())
        // A ride by part of its name.
        assertEquals(listOf("c1"), AskSearch.find(AskFilter(rides = listOf("sohna")), clips).map { it.momentId })
    }

    @Test
    fun `clips not looked at or not tagged yet are counted, so the screen can offer to do it`() {
        assertEquals(listOf("c1"), AskSearch.untagged(AskFilter(events = setOf(AskEvent.DOWNSHIFT)), clips).map { it.momentId })
        assertEquals(3, AskSearch.unseen(AskFilter(seen = listOf("rain")), clips).size)
        assertTrue(AskSearch.untagged(AskFilter(seen = listOf("rain")), clips).isEmpty())
    }

    @Test
    fun `a length keeps one from each ride first, newest first, then back in filming order`() {
        val hits = AskSearch.find(AskFilter(events = setOf(AskEvent.DOWNSHIFT)), clips)
        val (kept, capped) = AskSearch.fit(hits, 9)
        assertTrue(capped)
        // 3 s each: one from each of the three rides fits in 9 s.
        assertEquals(listOf("d1", "b1", "a1"), kept.map { it.momentId })
        assertEquals(false, AskSearch.fit(hits, null).second)
    }

    @Test
    fun `Gemini's answer is read, with questions whose answers change the search`() {
        val json = """{"title":"All my downshifts","said":[],"seen":[],"events":["downshift","nonsense"],"voice":false,"from":null,"to":null,"rides":[],"lengthSec":null,
          "questions":[{"text":"Since when?","why":"38 rides","answers":[{"label":"All rides","from":null},{"label":"Last month","from":"2026-10-24"}]},
                       {"text":"How long?","answers":[{"label":"15 s","lengthSec":15},{"label":"As long as it takes","lengthSec":null}]},
                       {"text":"Third?","answers":[{"label":"a"},{"label":"b"}]}]}"""
        val plan = assertNotNull(AskPrompt.parse(json, zone))
        assertEquals(setOf(AskEvent.DOWNSHIFT), plan.filter.events)
        assertEquals("All my downshifts", plan.filter.title)
        assertEquals(2, plan.questions.size)
        val f = plan.filter.copy(fromMillis = day("2026-01-01"), lengthSec = 30)
        assertNull(plan.questions[0].answers[0].patch.apply(f).fromMillis)
        assertEquals(day("2026-10-24"), plan.questions[0].answers[1].patch.apply(f).fromMillis)
        assertEquals(15, plan.questions[1].answers[0].patch.apply(f).lengthSec)
        assertNull(plan.questions[1].answers[1].patch.apply(f).lengthSec)
        assertNull(AskPrompt.parse("not json", zone))
        // A "to" date covers the whole day.
        val to = assertNotNull(AskPrompt.parse("""{"to":"2026-10-31"}""", zone)).filter.toMillis
        assertEquals(day("2026-11-01") - 1, to)
    }

    @Test
    fun `the menu names only what can be searched, and the script plays the parts in order`() {
        val menu = AskSearch.menu(clips, zone)
        assertTrue("downshift" in menu && "rain" in menu && "Nahan" in menu)
        assertTrue("2026-09-28" in menu)
        val hits = AskSearch.find(AskFilter(events = setOf(AskEvent.DOWNSHIFT)), clips)
        val sc = AskSearch.script("All my downshifts", hits)
        assertEquals(4, sc.sections.size)
        assertEquals("d1", sc.sections.first().shots.first().clip)
        assertEquals(AskPatch(clearDates = true).apply(AskFilter(fromMillis = 5)).fromMillis, null)
    }
}
