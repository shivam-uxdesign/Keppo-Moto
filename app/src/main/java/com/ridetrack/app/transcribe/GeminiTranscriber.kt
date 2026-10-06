package com.ridetrack.app.transcribe

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech to text with Google's Gemini API (the rider's own free key). The audio is sent to
 * Google; nothing else is. Hinglish comes back in English letters, as it's texted.
 */
class GeminiTranscriber(private val apiKey: String) {
    /** The key was refused (wrong, or Gemini isn't enabled for it): stop and tell the rider. */
    class BadKey(message: String) : IOException(message)

    /** Too many requests for the free tier right now: try again later. */
    class Busy(message: String) : IOException(message)

    /**
     * The best current "flash" model the key can use: newest version, and not a lite, preview,
     * image, audio-out or live variant. Model names change over time, so it's looked up.
     */
    fun pickModel(): String {
        val json = JSONObject(request("GET", "$BASE/models?pageSize=200&key=$apiKey", null))
        val names = (0 until (json.optJSONArray("models")?.length() ?: 0)).mapNotNull { i ->
            val m = json.getJSONArray("models").getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            m.getString("name").takeIf { "generateContent" in methods }
        }
        return chooseModel(names) ?: throw IOException("No Gemini flash model available for this key")
    }

    /** Transcript of [audio] ([mime], e.g. audio/mp4); "" when there's no clear speech. */
    fun transcribe(model: String, audio: ByteArray, mime: String): String {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
                        .put(JSONObject().put("text", PROMPT)),
                ),
            ),
        ).put("generationConfig", JSONObject().put("temperature", 0))
        val json = JSONObject(request("POST", "$BASE/$model:generateContent?key=$apiKey", body.toString()))
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text") }.trim()
        return cleanTranscript(text)
    }

    private fun request(method: String, url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")
            when {
                code in 200..299 -> return text
                code == 400 && "API key" in message || code == 401 || code == 403 -> throw BadKey(message)
                code == 429 || code == 503 -> throw Busy(message)
                else -> throw IOException(message)
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val NO_SPEECH = "[no speech]"

        val PROMPT = """
            Transcribe the rider's speech in this motorcycle helmet recording, exactly as spoken.
            The rider speaks English and Hinglish (Hindi mixed with English). Write everything in English letters (Latin script):
            write Hindi words the way people text them, for example "bhai ye road mast hai, aage traffic hai". Do not translate, and never use Devanagari.
            Ignore wind, engine, horn and traffic noise. If there is no clear speech, reply exactly: $NO_SPEECH
            Reply with only the transcript.
        """.trimIndent()

        /** Newest stable "flash" model among [names] ("models/gemini-2.5-flash", ...). */
        fun chooseModel(names: List<String>): String? {
            val skip = listOf("lite", "preview", "exp", "image", "tts", "audio", "live", "thinking", "8b", "embedding")
            val ok = names.filter { n -> "gemini" in n && "flash" in n && skip.none { it in n.lowercase() } }
            fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return ok.maxWithOrNull(compareBy<String>({ version(it) }, { -it.length }))
        }

        /** The model's reply as stored: "" for no speech; no quotes or "Transcript:" around it. */
        fun cleanTranscript(text: String): String {
            val t = text.trim().removePrefix("Transcript:").trim().trim('"', '“', '”').trim()
            return if (t.isEmpty() || t.equals(NO_SPEECH, ignoreCase = true) || t.contains(NO_SPEECH, ignoreCase = true) && t.length < 30) "" else t
        }
    }
}
