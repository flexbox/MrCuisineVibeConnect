package app.toque.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Le serveur a répondu, mais pas 200 (à distinguer d'un serveur injoignable). */
class HttpStatusException(val code: Int) : IOException(tr("réponse %d du serveur", code))

/** Téléchargements des synchros (reprises si le serveur sature) et photos réduites pour l'écran du robot. */
class Fetcher(private val version: String, private val repo: RecipeRepo) {
    /** Photo réduite (800 px de large au plus), enregistrée sous le nom de fichier de l'URL. */
    fun savePhoto(url: String) {
        val bytes = download(url, emptyMap(), 8 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException(tr("image illisible"))
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException(tr("image illisible"))
        if (bmp.width > MAX_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, (bmp.height * MAX_WIDTH / bmp.width).coerceAtLeast(1), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val name = url.substringAfterLast('/').substringBefore('?')
        val tmp = File(repo.imagesDir, "$name.part")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        if (!tmp.renameTo(File(repo.imagesDir, name))) tmp.delete()
    }

    /**
     * Photos manquantes des recettes [ids]. Une photo en échec n'arrête pas les autres ; cinq
     * échecs de réseau de suite arrêtent la série (elle reprendra à la prochaine synchro).
     */
    fun photos(ids: Set<String>, pauseMs: Long, log: (String) -> Unit, progress: (String) -> Unit) {
        val missing = repo.index.entries.filter { it.id in ids }.map { it.image }
            .filter { it.startsWith("http") && repo.imageFile(it) == null }
        var inARow = 0
        for ((i, url) in missing.withIndex()) {
            progress(tr("Photos : %d / %d", i + 1, missing.size))
            try {
                savePhoto(url)
                inARow = 0
            } catch (e: IOException) {
                log("photo $url : ${e.message}")
                if (++inARow >= 5) return
            } catch (e: Exception) {
                log("photo $url : $e")
            } catch (e: OutOfMemoryError) {
                log("photo $url : mémoire insuffisante")
            }
            pause(pauseMs)
        }
    }

    /** GET avec reprises espacées si le serveur demande de ralentir (429) ou est indisponible (5xx). */
    fun download(url: String, headers: Map<String, String>, maxBytes: Int): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            val c = app.toque.Tls.apply(URL(url).openConnection() as HttpURLConnection)
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", userAgent(version))
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val code = c.responseCode
                if ((code == 429 || code >= 500) && attempt < 4) {
                    pause(5_000L * attempt)
                    continue
                }
                if (code != 200) throw HttpStatusException(code)
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
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

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        const val MAX_WIDTH = 800
        const val PROJECT = "https://github.com/Wuki60/MrCuisineVibeConnect"

        fun userAgent(version: String) = "Toque/$version (Android; +$PROJECT)"
    }
}
