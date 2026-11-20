package app.toque.core.photos

import app.toque.core.json.Json
import app.toque.core.json.list

/**
 * Album Google Photos partagé par lien (photos.app.goo.gl/<code>), lu sans compte : la page
 * publique de l'album contient la liste de ses photos. Google ne documente pas ce format : s'il
 * change, [parse] lève une exception et le cadre photo annonce que l'album est illisible.
 * Une page liste au plus 300 éléments environ : d'un album plus long, seuls les premiers défilent.
 */
object SharedAlbum {
    class Photo(val url: String, val width: Int, val height: Int) {
        /** Adresse de la photo réduite pour tenir dans [maxWidth] × [maxHeight], sans rognage. */
        fun sized(maxWidth: Int, maxHeight: Int): String = "$url=w$maxWidth-h$maxHeight"
    }

    class Album(val title: String, val photos: List<Photo>)

    class UnreadableException(message: String) : Exception(message)

    /** Code du lien de partage, saisi seul ou avec le lien entier ; null s'il n'y en a pas. */
    fun code(input: String): String? {
        val last = input.trim().substringBefore('?').trimEnd('/').substringAfterLast('/')
        return last.takeIf { it.length in 6..64 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' } }
    }

    fun url(code: String): String = "https://photos.app.goo.gl/$code"

    /** Photos de la page d'un album partagé, dans l'ordre de l'album ; les vidéos sont écartées. */
    fun parse(html: String): Album {
        var from = 0
        while (true) {
            val start = html.indexOf(BLOCK, from)
            if (start < 0) throw UnreadableException("aucune liste de photos dans la page")
            from = start + BLOCK.length
            val data = html.indexOf("data:", from).takeIf { it >= 0 } ?: continue
            val end = html.indexOf(", sideChannel", data).takeIf { it >= 0 } ?: continue
            val json = html.substring(data + "data:".length, end)
            if (!json.contains(IMAGE_HOST)) continue
            val root = try {
                Json.parse(json).list()
            } catch (e: Json.ParseException) {
                throw UnreadableException("liste de photos illisible")
            }
            val photos = root.getOrNull(1).list().mapNotNull { photo(it.list()) }
            if (photos.isEmpty()) continue
            val title = root.getOrNull(3).list().getOrNull(1) as? String
            return Album(title.orEmpty(), photos)
        }
    }

    /** Élément d'album : [identifiant, [adresse, largeur, hauteur, …], date, …, {métadonnées}]. */
    private fun photo(item: List<Any?>): Photo? {
        val media = item.getOrNull(1).list()
        val url = media.getOrNull(0) as? String ?: return null
        if (!url.startsWith(IMAGE_HOST)) return null
        if (item.any { it is Map<*, *> && it.containsKey(VIDEO_KEY) }) return null
        val width = (media.getOrNull(1) as? Long)?.toInt() ?: return null
        val height = (media.getOrNull(2) as? Long)?.toInt() ?: return null
        return if (width > 0 && height > 0) Photo(url, width, height) else null
    }

    private const val BLOCK = "AF_initDataCallback("
    private const val IMAGE_HOST = "https://lh3.googleusercontent.com/"
    /** Clé des métadonnées que seules les vidéos portent. */
    private const val VIDEO_KEY = "76647426"
}
