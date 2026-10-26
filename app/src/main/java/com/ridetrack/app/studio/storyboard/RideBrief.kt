package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.MomentKeywords
import com.ridetrack.app.moments.MomentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What's in a ride, in a few lines for Keppo, so a Reel "from a ride" plans shots its clips can fill. */
object RideBrief {
    suspend fun of(c: AppContainer, rideId: String): String = withContext(Dispatchers.IO) {
        val ride = c.rides.get(rideId)
        val clips = c.moments.forRide(rideId).filter { it.kind == MomentKind.CLIP }
        brief(ride?.name.orEmpty(), clips.map { m ->
            Clip(
                seconds = ((m.durationMillis ?: 0) / 1000).toInt(),
                kinds = m.types.map { it.name.lowercase().replace('_', ' ') },
                words = MomentKeywords.shown(m.keywords.orEmpty()).take(5).map { it.text },
                said = m.transcript?.takeIf { it.isNotBlank() }?.take(120),
                road = m.camera == "back",
            )
        })
    }

    data class Clip(val seconds: Int, val kinds: List<String>, val words: List<String>, val said: String?, val road: Boolean)

    /** "Ride “Friday evening”, 16 clips: 1) 52 s selfie · voice, engine rev · chai, rain · said “…”; …" (at most 20 clips). */
    fun brief(name: String, clips: List<Clip>): String = buildString {
        append("Ride “${name.ifBlank { "Ride" }}”, ${clips.size} clip${if (clips.size != 1) "s" else ""}")
        if (clips.isEmpty()) { append(" (no clips: plan shots to film fresh)"); return@buildString }
        append(": ")
        append(clips.take(20).mapIndexed { i, cl ->
            listOfNotNull(
                "${i + 1}) ${cl.seconds} s ${if (cl.road) "road" else "selfie"}",
                cl.kinds.takeIf { it.isNotEmpty() }?.joinToString(", "),
                cl.words.takeIf { it.isNotEmpty() }?.joinToString(", "),
                cl.said?.let { "said “$it”" },
            ).joinToString(" · ")
        }.joinToString("; "))
    }
}
