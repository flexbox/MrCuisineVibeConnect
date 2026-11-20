package app.toque.core

import app.toque.core.machine.Accessory
import app.toque.core.recipes.Cookomix
import app.toque.core.recipes.StepProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversion des pages cookomix.com (page d'essai inventée, même structure que le site). */
class CookomixTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }

    @Test fun sitemaps() {
        val index = """<sitemapindex><sitemap><loc>https://www.cookomix.com/post-sitemap.xml</loc></sitemap>
            <sitemap><loc>https://www.cookomix.com/recipe-sitemap1.xml</loc></sitemap>
            <sitemap><loc>https://www.cookomix.com/recipe-sitemap2.xml</loc></sitemap>
            <sitemap><loc>https://www.cookomix.com/recipe_category-sitemap.xml</loc></sitemap></sitemapindex>"""
        assertEquals(listOf("https://www.cookomix.com/recipe-sitemap1.xml", "https://www.cookomix.com/recipe-sitemap2.xml"), Cookomix.recipeSitemaps(index))
        val xml = """<urlset><url><loc>https://www.cookomix.com/recettes/</loc></url>
            <url><loc>https://www.cookomix.com/recettes/soupe-thermomix/</loc><lastmod>2024-01-02T10:00:00+01:00</lastmod><image:image><image:loc>x.jpg</image:loc></image:image></url>
            <url><loc>https://www.cookomix.com/recettes/gateau-thermomix/</loc></url></urlset>"""
        val e = Cookomix.parseSitemap(xml)
        assertEquals(listOf("soupe-thermomix", "gateau-thermomix"), e.map { it.slug })
        assertEquals("2024-01-02T10:00:00+01:00", e[0].lastmod)
        assertEquals("", e[1].lastmod)
    }

    @Test fun recipe() {
        val res = Cookomix.parsePage(fixture("cookomix-page.html"), "https://www.cookomix.com/recettes/veloute-essai-thermomix/")
        val r = res.recipe!!
        assertEquals(Cookomix.Compat.ADAPTED, res.compat)
        assertEquals("ckx-123456", r.id)
        assertEquals("Velouté d’essai", r.title)
        assertEquals("Soupe", r.category)
        assertTrue(r.tags.containsAll(listOf("Cookomix", "Adaptée MCC", "Française")))
        assertEquals(4, r.servings)
        assertEquals(10, r.prepMinutes)
        assertEquals(35, r.totalMinutes)
        assertEquals("Cheffe d'essai", r.author)
        assertEquals(4.5, r.rating, 0.001)
        assertEquals(12, r.ratingCount)
        assertEquals("https://cdn.example.test/uploads/2024/01/veloute-essai-800x600.jpg", r.image)
        assertEquals(listOf("1 Oignon", "400 g Carottes", "500 ml Eau", "1 pincée Sel"), r.allIngredients.map { it.display })
        assertTrue(r.notes.contains("https://www.cookomix.com/recettes/veloute-essai-thermomix/"))
        assertTrue(r.notes.contains("98 °C arrondi à 95 °C"))
        assertTrue(r.notes.contains("vitesse 6 en chauffe ramenée à 3"))

        val machines = r.steps.mapNotNull { it.machine }
        // Mixer 5 s vitesse 5 : couvercle rattaché, texte adapté.
        val first = r.steps.first { it.machine != null }
        assertTrue(first.text, first.text == "Ajoutez le couvercle avec le gobelet doseur. Mixer.")
        assertEquals(5 to 0, machines[0].speed to machines[0].tempC)
        // 25 min 98 °C → 95 °C.
        assertEquals(95, machines[1].tempC)
        assertEquals(1500, machines[1].seconds)
        assertEquals(1, machines[1].speed)
        assertTrue(r.steps.single { it.machine === machines[1] }.text.endsWith("(Thermomix : 25 min / 98 °C / vitesse cuillère)"))
        // Vitesse 0,5 inverse → vitesse 1 inverse.
        assertEquals(1, machines[2].speed)
        assertTrue(machines[2].reverse)
        // Vitesse 6 en chauffe → 3.
        assertEquals(3, machines[3].speed)
        assertEquals(90, machines[3].tempC)
        // Varoma posé + vitesse 1 → 120 °C en manuel, lame en vitesse 1 (le programme Vapeur l'arrêterait).
        assertEquals(StepProgram.MANUAL, machines[4].program)
        assertEquals(120 to 1, machines[4].tempC to machines[4].speed)
        assertEquals(900, machines[4].seconds)
        // Vitesse cuillère : vitesse 1, et le texte ne dit jamais « vitesse 0 ».
        assertTrue(r.steps.none { Regex("vitesse 0(?![,.])").containsMatchIn(it.text) })
        // Pétrin 3 min 30 → 2 min + 1 min 30.
        assertEquals(listOf(StepProgram.KNEAD, StepProgram.KNEAD), machines.drop(5).take(2).map { it.program })
        assertEquals(listOf(120, 90), machines.drop(5).take(2).map { it.seconds })
        // Varoma sans le Varoma posé → 120 °C, réglage d'origine rappelé.
        assertEquals(StepProgram.MANUAL to 120, machines[7].program to machines[7].tempC)
        assertTrue(r.steps.single { it.machine === machines[7] }.text.endsWith("(Thermomix : 10 min / vapeur / sens inverse / vitesse 1)"))
        assertTrue(machines.all { it.valid })
        // Réglage modifié : le texte rappelle celui du Thermomix.
        assertTrue(r.steps.single { it.machine?.speed == 3 && it.machine?.tempC == 90 }.text.endsWith("(Thermomix : 1 min / 90 °C / vitesse 6)"))
        assertTrue(r.steps.filter { it.machine != null }.all { it.needsReview })
        // Balance pour les ingrédients en grammes, texte sans « Thermomix », minuteur retiré.
        assertEquals(400, r.steps.first { it.weigh != null }.weigh!!.grams)
        assertTrue(r.steps.none { it.text.replace("(Thermomix :", "").contains("Thermomix", ignoreCase = true) })
        assertTrue(r.steps.any { it.text == "Laisser reposer 10 min." })
        assertTrue(r.steps.any { it.text.contains("panier vapeur") })
    }


    @Test fun incompatible() {
        val html = fixture("cookomix-page.html").replace("\"mix_temp\":\"98\"", "\"mix_temp\":\"160\"")
        val res = Cookomix.parsePage(html, "https://www.cookomix.com/recettes/x/")
        assertNull(res.recipe)
        assertEquals(Cookomix.Compat.INCOMPATIBLE, res.compat)
        assertTrue(res.reasons.single().contains("160"))
        val unknown = fixture("cookomix-page.html").replace("\"mix_speed\":\"5\"", "\"mix_speed\":\"cuisson lente\"")
        assertEquals(Cookomix.Compat.INCOMPATIBLE, Cookomix.parsePage(unknown, "u").compat)
    }

    @Test fun compatibleAsIs() {
        val html = """<script type="application/ld+json">{"@type":"Recipe","name":"Purée","recipeIngredient":["Lait - 100 grammes"]}</script>
            <script>var stepbystep ={"title":"Purée","steps":[{"action":"mix","action_verb":"mixer","mix_duration":"20","mix_temp":"0","mix_speed":"4"}]};</script>"""
        val res = Cookomix.parsePage(html, "https://www.cookomix.com/recettes/puree-thermomix/")
        assertEquals(Cookomix.Compat.OK, res.compat)
        assertEquals("ckx-puree-thermomix", res.recipe!!.id)
        assertEquals(Accessory.BLADE, res.recipe!!.steps.single().machine!!.accessory)
        assertTrue("Adaptée MCC" !in res.recipe!!.tags)
    }

    @Test fun minutes() {
        assertEquals(30, Cookomix.minutes("PT30M"))
        assertEquals(80, Cookomix.minutes("PT1H20M"))
    }

    @Test fun unreadablePagesAreNotIncompatible() {
        val page = fixture("cookomix-page.html")
        // Page de maintenance, pas-à-pas disparu, champs renommés, JSON cassé : « illisible », jamais d'exception.
        for (html in listOf(
            "<html><body>Site en maintenance</body></html>",
            page.replace("var stepbystep", "var etapes"),
            page.replace("\"mix_speed\"", "\"speed\""),
            page.replace("\"action\":\"mix\"", "\"type\":\"mix\""),
            page.replace("var stepbystep ={", "var stepbystep ={{"),
            "",
        )) {
            val res = Cookomix.parsePage(html, "https://www.cookomix.com/recettes/x/")
            assertEquals(html.take(60), Cookomix.Compat.UNREADABLE, res.compat)
            assertNull(res.recipe)
        }
    }

    @Test fun listSanity() {
        assertTrue(Cookomix.listLooksWrong(0, 0))
        assertTrue(Cookomix.listLooksWrong(2437, 0))
        assertTrue(Cookomix.listLooksWrong(2437, 1000))
        assertTrue(!Cookomix.listLooksWrong(2437, 2400))
        assertTrue(!Cookomix.listLooksWrong(0, 2437))
        assertTrue(!Cookomix.listLooksWrong(50, 10))
    }

    @Test fun breaker() {
        // Fonctionnement normal : quelques incompatibles, aucune alerte.
        val ok = Cookomix.Breaker()
        repeat(100) { ok.record(if (it % 10 == 0) Cookomix.Compat.INCOMPATIBLE else Cookomix.Compat.OK) }
        assertNull(ok.verdict())
        // 10 pages illisibles de suite : arrêt immédiat.
        val row = Cookomix.Breaker()
        repeat(9) { row.record(Cookomix.Compat.UNREADABLE) }
        assertNull(row.verdict())
        row.record(Cookomix.Compat.UNREADABLE)
        assertEquals(Cookomix.Error.PAGES_UNREADABLE, row.verdict())
        // Un tiers de pages illisibles, dispersées.
        val spread = Cookomix.Breaker()
        repeat(30) { spread.record(if (it % 3 == 0) Cookomix.Compat.UNREADABLE else Cookomix.Compat.OK) }
        assertEquals(Cookomix.Error.PAGES_UNREADABLE, spread.verdict())
        // Presque tout refusé : format des réglages changé.
        val refused = Cookomix.Breaker()
        repeat(20) { refused.record(if (it < 15) Cookomix.Compat.INCOMPATIBLE else Cookomix.Compat.OK) }
        assertEquals(Cookomix.Error.TOO_MANY_INCOMPATIBLE, refused.verdict())
    }

    @Test fun errorCodes() {
        val codes = Cookomix.Error.values().map { it.code }
        assertEquals(codes.distinct(), codes)
        assertTrue(codes.all { Regex("CKX-\\d\\d").matches(it) })
        assertTrue(Cookomix.Error.PAGES_UNREADABLE.message("12 pages lues").startsWith("CKX-05 : "))
        // La doc décrit chaque code.
        val doc = java.io.File("../docs/COOKOMIX-ERREURS.md").readText()
        for (c in codes) assertTrue("$c absent de docs/COOKOMIX-ERREURS.md", doc.contains("| $c |"))
    }

    private fun page(vararg steps: String) =
        """<script type="application/ld+json">{"@type":"Recipe","name":"Essai","recipeIngredient":["Lait - 100 grammes"]}</script>
        <script>var stepbystep ={"title":"Essai","steps":[${steps.joinToString(",")}]};</script>"""

    private fun mix(speed: String, temp: String = "0", s: Int = 30) =
        """{"action":"mix","action_verb":"mélanger","mix_duration":"$s","mix_temp":"$temp","mix_speed":"$speed"}"""

    @Test fun whiskIsTrackedForSafety() {
        // Fouet posé par une étape texte : vitesse limitée à 4, puis la lame revient quand on le retire.
        val r = Cookomix.parsePage(page(
            """{"action":"other","action_desc":"Insérer le fouet papillon."}""", mix("6"),
            """{"action":"other","action_desc":"Retirer le fouet."}""", mix("8")), "u")
        val m = r.recipe!!.steps.mapNotNull { it.machine }
        assertEquals(listOf(Accessory.WHISK, Accessory.BLADE), m.map { it.accessory })
        assertEquals(listOf(4, 8), m.map { it.speed })
        assertEquals(Cookomix.Compat.ADAPTED, r.compat)
        // « crème fouettée », « fouetter » : pas l'accessoire.
        val cream = Cookomix.parsePage(page("""{"action":"add","action_verb":"Ajouter","action_desc":"la crème fouettée, puis fouetter"}""", mix("8")), "u")
        assertEquals(Accessory.BLADE, cream.recipe!!.steps.mapNotNull { it.machine }.single().accessory)
        // Turbo avec le fouet en place : le réglage garde le fouet, la sécurité de Toque le refuse.
        val turbo = Cookomix.parsePage(page("""{"action":"add-accessory","addaccessory":"butterfly-whisk","action_desc":"Ajouter le fouet."}""", mix("turbo", s = 2)), "u")
        val t = turbo.recipe!!.steps.mapNotNull { it.machine }.single()
        assertEquals(StepProgram.TURBO, t.program)
        assertEquals(Accessory.WHISK, t.accessory)
        assertTrue(!t.valid)
        // Deux verbes dans la phrase : celui qui précède « fouet » décide.
        val both = Cookomix.parsePage(page("""{"action":"other","action_desc":"Retirer le gobelet doseur et insérer le fouet."}""", mix("6")), "u")
        assertEquals(Accessory.WHISK, both.recipe!!.steps.mapNotNull { it.machine }.single().accessory)
        // Un réglage qui dit de retirer le fouet ensuite tourne encore avec lui ; le suivant, non.
        val after = Cookomix.parsePage(page(
            """{"action":"other","action_desc":"Insérer le fouet."}""",
            """{"action":"mix","action_verb":"fouetter","action_desc":"puis retirer le fouet","mix_duration":"30","mix_temp":"0","mix_speed":"6"}""",
            mix("8")), "u")
        assertEquals(listOf(Accessory.WHISK, Accessory.BLADE), after.recipe!!.steps.mapNotNull { it.machine }.map { it.accessory })
        // Accessoire inconnu : recette refusée plutôt que de deviner.
        val unknown = Cookomix.parsePage(page("""{"action":"add-accessory","addaccessory":"spiralizer","action_desc":"Ajouter l'accessoire."}""", mix("4")), "u")
        assertEquals(Cookomix.Compat.INCOMPATIBLE, unknown.compat)
    }

    @Test fun speedThatIsNotANumberIsRefused() {
        assertEquals(Cookomix.Compat.INCOMPATIBLE, Cookomix.parsePage(page(mix("NaN")), "u").compat)
    }

    @Test fun halfSpeedsAreFlagged() {
        val r = Cookomix.parsePage(page(mix("3.5")), "u")
        assertEquals(Cookomix.Compat.ADAPTED, r.compat)
        val st = r.recipe!!.steps.single()
        assertEquals(4, st.machine!!.speed)
        assertTrue(st.text, st.text.endsWith("(Thermomix : 30 s / vitesse 3,5)"))
        // Cuillère → 1 : équivalence documentée, pas une adaptation, mais le réglage d'origine est rappelé.
        val spoon = Cookomix.parsePage(page(mix("0", "100")), "u")
        assertEquals(Cookomix.Compat.OK, spoon.compat)
        assertTrue(spoon.recipe!!.steps.single().text.endsWith("vitesse cuillère)"))
    }

    @Test fun unparsableSettingIsUnreadable() {
        for (bad in listOf(
            """{"action":"mix","action_verb":"cuire","mix_duration":"1:30","mix_temp":"100","mix_speed":"1"}""",
            """{"action":"mix","action_verb":"cuire","mix_duration":"90","mix_temp":"100","mix_speed":""}""",
            """{"action":"mix","action_verb":"cuire","mix_duration":"90","mix_speed":"1"}""",
            // Durées absurdes : ne pas en faire des milliers d'étapes.
            """{"action":"mix","action_verb":"cuire","mix_duration":"1e12","mix_temp":"100","mix_speed":"1"}""",
            """{"action":"mix","action_verb":"cuire","mix_duration":"Infinity","mix_temp":"100","mix_speed":"1"}""",
        )) assertEquals(bad, Cookomix.Compat.UNREADABLE, Cookomix.parsePage(page(bad), "u").compat)
    }

    @Test fun entities() {
        assertEquals("L’été 😀 &#39;", app.toque.core.recipes.McOnline.plain("L&rsquo;&#233;t&#233; &#128512; &amp;#39;"))
    }
}
