package app.toque

import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GET minimal (heure, avis, photos) : corps, et heure du serveur (en-tête Date, 0 si absente). */
object Http {
    class Response(val body: String, val serverMs: Long)

    var userAgent = "Toque (Android; +https://github.com/Wuki60/MrCuisineVibeConnect)"

    /** Heure du serveur (en-tête Date, 0 si absente), quelle que soit sa réponse ; le corps n'est pas lu. */
    fun serverDate(url: String, timeoutMs: Int = 15_000): Long {
        val c = Tls.apply(URL(url).openConnection() as HttpURLConnection)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            c.responseCode
            return c.date
        } finally {
            c.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000, maxBytes: Int = 1024 * 1024): Response {
        var serverMs = 0L
        val body = bytes(url, headers, timeoutMs, maxBytes) { serverMs = it }
        return Response(String(body, Charsets.UTF_8), serverMs)
    }

    /** Corps de la réponse tel quel (image…) ; [onDate] reçoit l'heure du serveur. */
    fun bytes(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000, maxBytes: Int = 1024 * 1024,
              onDate: (Long) -> Unit = {}): ByteArray {
        val c = Tls.apply(URL(url).openConnection() as HttpURLConnection)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            onDate(c.date)
            if (code != 200) throw IOException(tr("réponse %d du serveur", code))
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException(tr("réponse trop grande"))
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}
