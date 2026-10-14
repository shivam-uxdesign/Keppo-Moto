package com.ridetrack.app.studio

import android.content.Context
import android.net.Uri
import com.ridetrack.telemetry.moments.EngineNoise
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The horns and bumps in an engine sound file ([EngineNoise]), worked out once and kept next to
 * it ("<file>.noise.json"). Read by the renderer (to turn them down) and the editor (to show them).
 */
object EngineNoises {
    private const val VERSION = 1

    fun cacheOf(file: File) = File(file.parentFile, file.name + ".noise.json")

    /** The noises in the file behind [uri] (a file: URI); empty when it can't be read. */
    fun ofUri(context: Context, uri: String): List<EngineNoise.Noise> =
        Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let { of(context, File(it)) }.orEmpty()

    /** Only what's already worked out (no decoding): null when it isn't yet. */
    fun cached(file: File): List<EngineNoise.Noise>? = read(cacheOf(file))

    @Synchronized
    fun of(context: Context, file: File): List<EngineNoise.Noise> {
        if (!file.isFile) return emptyList()
        read(cacheOf(file))?.let { return it }
        val (pcm, rate) = EngineAudio.decode(context, file, null) ?: return emptyList()
        val pitches = Revs.pitches(pcm, rate)
        val found = EngineNoise.find(pcm, rate) { ms -> pitches.getOrNull((ms / Revs.HOP_MS).toInt()) }
        runCatching { cacheOf(file).writeAtomically(write(found)) }
        return found
    }

    private fun write(list: List<EngineNoise.Noise>) = JSONObject().put("v", VERSION).put("noise", JSONArray().apply {
        list.forEach { n -> put(JSONObject().put("kind", n.kind.name).put("from", n.startMs).put("to", n.endMs).put("hz", JSONArray(n.freqsHz.map { it.toDouble() }))) }
    }).toString()

    private fun read(f: File): List<EngineNoise.Noise>? = runCatching {
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        if (o.optInt("v") != VERSION) return null
        val a = o.getJSONArray("noise")
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { n ->
                EngineNoise.Noise(
                    EngineNoise.Kind.valueOf(n.getString("kind")), n.getLong("from"), n.getLong("to"),
                    n.optJSONArray("hz")?.let { h -> (0 until h.length()).map { h.getDouble(it).toFloat() } }.orEmpty(),
                )
            }
        }
    }.getOrNull()
}
