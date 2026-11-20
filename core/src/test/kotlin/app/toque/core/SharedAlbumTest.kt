package app.toque.core

import app.toque.core.photos.SharedAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAlbumTest {
    private fun item(id: String, url: String, w: Int, h: Int, meta: String = """{"101428965":[1]}""") =
        """["$id",["$url",$w,$h,null,null,null,null,null,[null,null,1],[5]],1700000000000,"x",3600000,1700000001000,["k"],$meta]"""

    /** Page d'album inventée, à la forme de celles de Google Photos (bloc de compte, puis bloc des photos). */
    private fun page(vararg items: String, title: String = "Vacances") = """<html><head><title>$title - Google Photos</title></head><body>
        <script nonce="n">AF_initDataCallback({key: 'ds:0', hash: '1', data:[null,"https://lh3.googleusercontent.com/a/avatar",[1,2]], sideChannel: {}});</script>
        <script nonce="n">AF_initDataCallback({key: 'ds:1', hash: '2', data:[null,[${items.joinToString(",")}],"",["albumid","$title",[1700000000000,1700000001000]],null,2], sideChannel: {}});</script>
        </body></html>"""

    private val host = "https://lh3.googleusercontent.com/pw"

    @Test fun photosAreListedInAlbumOrder() {
        val album = SharedAlbum.parse(page(item("a", "$host/AAA", 1280, 853), item("b", "$host/BBB", 853, 1280)))
        assertEquals("Vacances", album.title)
        assertEquals(listOf("$host/AAA", "$host/BBB"), album.photos.map { it.url })
        assertEquals(listOf(1280 to 853, 853 to 1280), album.photos.map { it.width to it.height })
        assertEquals("$host/AAA=w1024-h600", album.photos[0].sized(1024, 600))
    }

    @Test fun videosAreLeftOut() {
        val video = item("v", "$host/VVV", 1920, 1080, """{"101428965":[1],"76647426":[30000,1920,1080,3]}""")
        val album = SharedAlbum.parse(page(item("a", "$host/AAA", 1280, 853), video))
        assertEquals(listOf("$host/AAA"), album.photos.map { it.url })
    }

    @Test fun pagesWithoutPhotosAreUnreadable() {
        for (html in listOf("", "<html>Connexion</html>", page(), page(item("x", "https://example.org/x", 10, 10)),
            page(item("a", "$host/AAA", 1280, 853)).replace("],\"\",[", "],,,["))) {
            val refused = try {
                SharedAlbum.parse(html)
                false
            } catch (_: SharedAlbum.UnreadableException) {
                true
            }
            assertTrue(html.take(40), refused)
        }
    }

    @Test fun codeIsReadFromALinkOrAlone() {
        assertEquals("AbCd1234efGH", SharedAlbum.code("AbCd1234efGH"))
        assertEquals("AbCd1234efGH", SharedAlbum.code(" https://photos.app.goo.gl/AbCd1234efGH "))
        assertEquals("AbCd1234efGH", SharedAlbum.code("photos.app.goo.gl/AbCd1234efGH/?x=1"))
        assertEquals("https://photos.app.goo.gl/AbCd1234efGH", SharedAlbum.url("AbCd1234efGH"))
        assertNull(SharedAlbum.code(""))
        assertNull(SharedAlbum.code("mes vacances"))
        assertNull(SharedAlbum.code("https://photos.app.goo.gl/"))
        assertNull(SharedAlbum.code("abc"))
    }
}
