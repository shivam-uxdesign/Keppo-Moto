package com.ridetrack.app.studio

import org.json.JSONArray
import org.json.JSONObject

/** Post text for each place: Instagram's caption, YouTube's title and description. */
data class PostTexts(val instagram: String = "", val youtubeTitle: String = "", val youtubeDescription: String = "")

/** Whether the first 3 seconds would stop the scroll, why, and what to change. */
data class HookCheck(val stops: Boolean, val why: String, val fix: String?)

/** Learning from what did well, post text, subtitles and the hook check. Pure, unit-tested. */
object Posting {
    /**
     * What did well for the rider, for Gemini suggesting the next pieces: their Reels with views,
     * best first, as "“Title” (reel, 30 s, story, Hype) 12,400 views, 900 likes".
     */
    fun performance(reels: List<ReelProject>, n: Int = 5): String {
        val rated = reels.filter { it.deletedAt == null && (it.views ?: 0) > 0 }.sortedByDescending { it.views }
        if (rated.size < 2) return ""
        val lines = rated.take(n).map { p ->
            val sc = p.script
            "\"${p.title}\" (${sc?.format?.label?.lowercase() ?: "reel"}, ${p.durationMs / 1000} s, ${sc?.shape ?: "edit"}, ${p.vibe.label}) ${p.views} views" + (p.likes?.let { ", $it likes" } ?: "")
        }
        val worst = rated.last().takeIf { rated.size > n }?.let { "; least: \"${it.title}\" ${it.views} views" }.orEmpty()
        return "What did best for this rider (do more like these): " + lines.joinToString("; ") + worst + ".\n"
    }

    fun postPrompt(title: String, about: String, caption: String): String =
        "Write post text for a motorcycle rider's short video (Hinglish is fine). Title: \"$title\". What's in it: $about. Their Instagram caption: \"$caption\". " +
            "Reply with JSON only: {\"instagram\":\"caption, 1–2 lines + 3–5 hashtags\",\"youtubeTitle\":\"under 70 characters, with #shorts\",\"youtubeDescription\":\"2–4 lines and hashtags\"}"

    fun parsePost(reply: String): PostTexts? = runCatching {
        val o = obj(reply)
        PostTexts(o.optString("instagram").trim(), o.optString("youtubeTitle").trim().take(100), o.optString("youtubeDescription").trim())
    }.getOrNull()?.takeIf { it.instagram.isNotEmpty() || it.youtubeTitle.isNotEmpty() }

    fun translatePrompt(lines: List<String>, lang: String): String =
        "Translate these spoken lines from a motorcycle ride video into $lang subtitles: short, natural, same order, one for each. " +
            (if (lang == "Hindi") "Use Devanagari. " else "") +
            "Reply with JSON only: {\"lines\":[...]} with exactly ${lines.size} items. Lines: " + JSONArray(lines).toString()

    fun parseTranslation(reply: String, n: Int): List<String>? = runCatching {
        val a = obj(reply).getJSONArray("lines")
        if (a.length() != n) return null
        (0 until n).map { a.getString(it).trim() }
    }.getOrNull()

    /** Every caption line in the edit, in order, and a way to put translations back in their place. */
    fun captionTexts(plan: StudioPlan): List<String> = plan.segments.filterIsInstance<ClipSegment>().flatMap { s -> s.lines.map { it.text } }

    fun withCaptions(plan: StudioPlan, texts: List<String>): StudioPlan {
        var i = 0
        return plan.copy(segments = plan.segments.map { seg ->
            if (seg is ClipSegment) seg.copy(lines = seg.lines.map { l -> l.copy(text = texts.getOrNull(i++)?.ifBlank { null } ?: l.text) }) else seg
        })
    }

    fun hookPrompt(hookLine: String): String =
        "These are three frames from the first 3 seconds of a motorcycle rider's Reel, watched with the sound off. The on-screen hook line is \"$hookLine\". " +
            "Would it stop someone scrolling? Be honest and brief. Reply with JSON only: {\"stops\":true,\"why\":\"one line\",\"fix\":\"one concrete change, or empty\"}"

    fun parseHook(reply: String): HookCheck? = runCatching {
        val o = obj(reply)
        HookCheck(o.getBoolean("stops"), o.optString("why").trim().ifEmpty { if (o.getBoolean("stops")) "It should stop the scroll." else "It may not stop the scroll." }, o.optString("fix").trim().takeIf { it.isNotEmpty() })
    }.getOrNull()

    private fun obj(reply: String): JSONObject {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        return JSONObject(reply.substring(a, b + 1))
    }
}
