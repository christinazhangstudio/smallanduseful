package dev.lystic.fanyixia

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

internal object Publisher {
    const val DEFAULT_SERVER = "https://smallanduseful.lystic.dev"

    fun normalizeServer(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "Enter an HTTPS server URL without credentials, query, or fragment."
        }
        return uri.toASCIIString().trimEnd('/')
    }

    fun publish(server: String, session: SavedSession): String {
        val connection = URL("${normalizeServer(server)}/api/translation-sessions/${session.id}")
            .openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = session.payload().toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Server response was too large. Local session is safe." }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
                error("Publish failed (HTTP $status). ${detail.orEmpty()} Local data kept; retry this session safely.")
            }
            val published = JSONObject(body).getJSONObject("session")
            check(published.getString("id") == session.id && published.getString("title") == session.title &&
                published.getString("chinese") == session.chinese && published.getString("english") == session.english &&
                Instant.parse(published.getString("recordedAt")) == Instant.parse(session.recordedAt) &&
                published.getString("transcriptionEngine") == "whisper-base" && published.getString("translationEngine") == "ml-kit") {
                "Server acknowledgement did not match the saved session. Local data kept; retry safely."
            }
            return published.getString("publishedAt").also { Instant.parse(it) }
        } finally {
            connection.disconnect()
        }
    }
}
