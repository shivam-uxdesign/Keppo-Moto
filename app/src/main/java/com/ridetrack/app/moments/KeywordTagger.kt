package com.ridetrack.app.moments

import com.ridetrack.app.AppContainer
import com.ridetrack.app.studio.ClipDownshifts
import com.ridetrack.app.ui.format.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Puts keywords on a ride's clips once it's saved, and on older clips once (see [catchUp]). */
object KeywordTagger {
    /** Ride keywords (and downshifts) for the clips of [rideId] that have none yet. */
    suspend fun tagRide(c: AppContainer, rideId: String): Int = withContext(Dispatchers.IO) {
        val samples = c.rides.track(rideId).samples
        if (samples.isEmpty()) return@withContext 0
        val lean = c.settings.settings.first().moments.leanDeg
        val n = c.moments.fillKeywords(rideId, samples, lean, Units.miles) { lat, lon -> runCatching { c.places.placeAt(lat, lon) }.getOrNull() }
        if (n > 0) {
            addDownshifts(c, rideId)
            addShouts(c, rideId)
            addScenes(c, rideId)
        }
        n
    }

    /** What's in the picture, from the on-phone labeller. */
    private suspend fun addScenes(c: AppContainer, rideId: String) {
        c.moments.forRide(rideId).filter { it.kind == MomentKind.CLIP && it.file.isFile }.forEach { m ->
            if (m.keywords.orEmpty().any { it.kind == MomentKeyword.Kind.SCENE }) return@forEach
            val labels = runCatching { SceneLabels.of(m.file) }.getOrDefault(emptyList())
            if (labels.isNotEmpty()) c.moments.addKeywords(m.id, labels.map { MomentKeyword(it, MomentKeyword.Kind.SCENE) })
        }
    }

    /** "Shouted" on talking clips that got very loud, measured on the phone (no Gemini needed). */
    private suspend fun addShouts(c: AppContainer, rideId: String) {
        c.moments.forRide(rideId).filter { it.kind == MomentKind.CLIP && com.ridetrack.telemetry.model.RideEventType.VOICE in it.types && it.file.isFile }.forEach { m ->
            if (m.keywords.orEmpty().any { it.kind == MomentKeyword.Kind.SOUND }) return@forEach
            val loud = runCatching { com.ridetrack.app.studio.EngineAudio.decode(c.appContext, m.file, null)?.let { (pcm, rate) -> MomentKeywords.loud(pcm, rate) } }.getOrNull() == true
            if (loud) c.moments.addKeywords(m.id, MomentKeywords.sound(emptyList(), emptyList(), shouted = true))
        }
    }

    /** "Downshift ×3" from the engine-sound downshifts, on clips that have them. */
    private suspend fun addDownshifts(c: AppContainer, rideId: String) {
        c.moments.forRide(rideId).filter { it.kind == MomentKind.CLIP }.forEach { m ->
            val count = ClipDownshifts.load(m.file)?.size ?: return@forEach
            if (count == 0) return@forEach
            val list = c.moments.keywords(m.id) ?: return@forEach
            val text = if (count == 1) "Downshift" else "Downshift ×$count"
            if (list.any { it.key.startsWith("downshift") }) return@forEach
            c.moments.setKeywords(m.id, list + MomentKeyword(text, MomentKeyword.Kind.RIDE, count.toDouble()))
        }
    }

    /** Older clips (before keywords existed), a ride at a time; called while charging. */
    suspend fun catchUp(c: AppContainer, maxRides: Int = 20): Int {
        val rides = c.moments.untagged().map { it.rideId }.distinct().take(maxRides)
        return rides.sumOf { runCatching { tagRide(c, it) }.getOrDefault(0) }
    }
}
