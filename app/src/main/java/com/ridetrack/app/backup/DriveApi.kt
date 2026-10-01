package com.ridetrack.app.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Drive said no. [code] is the HTTP status; 401 means the access token needs renewing. */
class DriveException(val code: Int, message: String) : IOException("Drive $code: $message") {
    val retryable get() = code == 429 || code >= 500
}

/** A file or folder in Drive. */
data class DriveFile(val id: String, val name: String, val size: Long?, val folder: Boolean)

/** Query strings, kept pure so they're unit-tested. */
object DriveQuery {
    const val FOLDER = "application/vnd.google-apps.folder"
    const val TAG = "keppo"

    /** Keppo's folders are found by a private tag, never by name, so renaming or moving them doesn't matter. */
    fun folderByTag(value: String) =
        "mimeType = '$FOLDER' and trashed = false and appProperties has { key='$TAG' and value='$value' }"

    fun children(parentId: String) = "'$parentId' in parents and trashed = false"
}

/**
 * Just enough Drive v3 for backup, over plain HTTPS. Only `drive.file` is ever granted, so it
 * sees only what Keppo apps created. [token] returns a current access token.
 */
class DriveApi(private val token: suspend () -> String) {

    suspend fun findFolderByTag(tag: String): DriveFile? =
        list(DriveQuery.folderByTag(tag)).firstOrNull()

    suspend fun createFolder(name: String, tag: String, parentId: String?): DriveFile {
        val meta = JSONObject().put("name", name).put("mimeType", DriveQuery.FOLDER)
            .put("appProperties", JSONObject().put(DriveQuery.TAG, tag))
        if (parentId != null) meta.put("parents", org.json.JSONArray().put(parentId))
        val res = request("POST", "$API/files?fields=$FIELDS", meta.toString().toByteArray(), "application/json")
        return parse(JSONObject(res))
    }

    suspend fun children(parentId: String): List<DriveFile> = list(DriveQuery.children(parentId))

    /** Creates or replaces a small file (JSON, settings) in one request. */
    suspend fun putSmall(parentId: String, name: String, bytes: ByteArray, mime: String, existingId: String?): DriveFile {
        if (existingId != null) {
            val res = request("PATCH", "$UPLOAD/files/$existingId?uploadType=media&fields=$FIELDS", bytes, mime)
            return parse(JSONObject(res))
        }
        val boundary = "keppo-${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parentId)).toString()
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").append(meta).append("\r\n")
            append("--$boundary\r\nContent-Type: $mime\r\n\r\n")
        }.toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val res = request("POST", "$UPLOAD/files?uploadType=multipart&fields=$FIELDS", body, "multipart/related; boundary=$boundary")
        return parse(JSONObject(res))
    }

    /** Uploads a (possibly large) file in [CHUNK] pieces, so a dropped connection costs one chunk, not the clip. */
    suspend fun putLarge(parentId: String, name: String, file: File, mime: String, existingId: String?): DriveFile = withContext(Dispatchers.IO) {
        val meta = if (existingId == null) JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parentId)) else JSONObject()
        val start = if (existingId == null) "$UPLOAD/files?uploadType=resumable&fields=$FIELDS" else "$UPLOAD/files/$existingId?uploadType=resumable&fields=$FIELDS"
        val session = open(if (existingId == null) "POST" else "PATCH", start).apply {
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mime)
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
            doOutput = true
            outputStream.use { it.write(meta.toString().toByteArray()) }
        }
        check(session)
        val location = session.getHeaderField("Location") ?: throw DriveException(500, "No upload session")
        session.disconnect()
        val total = file.length()
        var sent = 0L
        var result: String? = null
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK)
            while (result == null) {
                val n = readFully(input, buf)
                val end = sent + n - 1
                val c = open("PUT", location).apply {
                    doOutput = true
                    setFixedLengthStreamingMode(n)
                    setRequestProperty("Content-Range", if (n == 0) "bytes */$total" else "bytes $sent-$end/$total")
                    outputStream.use { it.write(buf, 0, n) }
                }
                when (c.responseCode) {
                    200, 201 -> result = c.inputStream.bufferedReader().use { it.readText() }
                    308 -> sent = c.getHeaderField("Range")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: (sent + n)
                    else -> check(c)
                }
                c.disconnect()
                if (result == null && sent < end + 1) {
                    // The server kept less than we sent: rewind to where it stopped.
                    input.channel.position(sent)
                }
            }
        }
        parse(JSONObject(result!!))
    }

    suspend fun download(fileId: String, out: OutputStream) = withContext(Dispatchers.IO) {
        val c = open("GET", "$API/files/$fileId?alt=media")
        check(c)
        c.inputStream.use { it.copyTo(out) }
        c.disconnect()
    }

    suspend fun downloadText(fileId: String): String {
        val out = java.io.ByteArrayOutputStream()
        download(fileId, out)
        return out.toString(Charsets.UTF_8.name())
    }

    suspend fun delete(fileId: String) {
        try {
            request("DELETE", "$API/files/$fileId", null, null)
        } catch (e: DriveException) {
            if (e.code != 404) throw e
        }
    }

    // ---- plumbing --------------------------------------------------------------------------

    private suspend fun list(q: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$API/files?q=${enc(q)}&spaces=drive&pageSize=1000&fields=${enc("nextPageToken,files($FIELDS)")}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val o = JSONObject(request("GET", url, null, null))
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) out += parse(files.getJSONObject(i))
            page = o.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        return out
    }

    private suspend fun request(method: String, url: String, body: ByteArray?, type: String?): String = withContext(Dispatchers.IO) {
        val c = open(method, url)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        check(c)
        val text = if (c.responseCode == 204) "" else c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        text
    }

    private suspend fun open(method: String, url: String): HttpURLConnection {
        val t = token()
        return (URL(url).openConnection() as HttpURLConnection).apply {
            // HttpURLConnection has no PATCH; Drive accepts the override header.
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $t")
        }
    }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 200..299) return
        val msg = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        c.disconnect()
        throw DriveException(code, msg.take(300))
    }

    private fun readFully(input: java.io.FileInputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun parse(o: JSONObject) = DriveFile(
        id = o.getString("id"),
        name = o.optString("name"),
        size = if (o.has("size")) o.optString("size").toLongOrNull() else null,
        folder = o.optString("mimeType") == DriveQuery.FOLDER,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FIELDS = "id,name,size,mimeType"
        const val CHUNK = 8 * 1024 * 1024 // 8 MB, a multiple of 256 KB as Drive requires
    }
}
