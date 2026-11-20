package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.double
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.Accessory
import app.toque.core.machine.Limits
import app.toque.core.machine.SafetyPolicy
import app.toque.core.protocol.Mcc

/**
 * Recettes de cookomix.com, lues sur les pages publiques du site puis converties pour le
 * Monsieur Cuisine connect.
 *
 * Cookomix n'a pas d'API publique (son API mobile exige une clé privée). Chaque page de recette
 * contient en revanche un bloc JSON-LD (titre, ingrédients, temps, note) et l'objet `stepbystep`
 * de son mode pas-à-pas, où chaque réglage robot est structuré (durée, °C, vitesse, sens inverse).
 * La liste des recettes et leur date de mise à jour viennent des sitemaps du site.
 *
 * Les recettes sont écrites pour le Thermomix : chaque réglage est traduit vers le MCC et la
 * recette reçoit un verdict ([Compat]) : compatible telle quelle, adaptée (un réglage a dû être
 * modifié pour respecter les limites du MCC) ou incompatible (non importée). Une page dont la
 * structure n'est pas celle attendue est « illisible » ([Compat.UNREADABLE]) : ce n'est pas un
 * verdict sur la recette, la synchro la garde telle quelle et réessaiera.
 *
 * Les échecs de synchro ont un code documenté ([Error], docs/COOKOMIX-ERREURS.md).
 */
object Cookomix {
    const val SITE = "https://www.cookomix.com"
    const val SITEMAP_INDEX = "$SITE/sitemap_index.xml"
    /** Préfixe des identifiants Toque des recettes Cookomix. */
    const val ID_PREFIX = "ckx-"
    const val TAG = "Cookomix"
    const val TAG_ADAPTED = "Adaptée MCC"

    /**
     * Version des règles de conversion. À augmenter quand elles changent le réglage produit pour
     * une même page : les recettes déjà importées sont alors relues à la synchro suivante.
     */
    const val CONVERSION = 1

    private const val MAX_KNEAD_S = (Limits.MAX_MS_KNEAD / 1000).toInt()
    private const val MAX_MANUAL_S = (Limits.MAX_MS_DEFAULT / 1000).toInt()
    private const val MAX_HIGH_SPEED_S = (Limits.MAX_MS_HIGH_SPEED / 1000).toInt()
    private const val MAX_TEMP_C = 130
    /** Au-delà, la durée d'une étape n'est pas crédible : la page est tenue pour illisible. */
    private const val MAX_SOURCE_S = 12 * 3600.0
    private val WHISK_KEYS = setOf("butterfly-whisk", "butterfly", "whisk", "papillon", "fouet")
    private val WHISK_WORD = Regex("\\bfouet\\b")
    private val REMOVE_VERBS = Regex("\\b(retirer|retirez|enlever|enlevez|ôter|ôtez|sortir|sortez)\\b")
    private val INSERT_VERBS = Regex("\\b(insérer|insérez|mettre|mettez|placer|placez|ajouter|ajoutez|fixer|fixez|monter|montez)\\b")
    /** Accessoires sans effet sur les limites de vitesse (Varoma, panier). */
    private val NEUTRAL_ACCESSORIES = setOf("varoma", "basket", "panier", "steamer")

    /** Verdict d'une page : UNREADABLE = format inattendu (site changé ?), pas une recette incompatible. */
    enum class Compat { OK, ADAPTED, INCOMPATIBLE, UNREADABLE }

    // ------------------------------------------------------------------ erreurs

    /** Codes d'erreur affichés à l'utilisateur et décrits dans docs/COOKOMIX-ERREURS.md. */
    enum class Error(val code: String) {
        NETWORK("CKX-01"),
        HTTP("CKX-02"),
        LIST_UNREADABLE("CKX-03"),
        LIST_SHRUNK("CKX-04"),
        PAGES_UNREADABLE("CKX-05"),
        TOO_MANY_INCOMPATIBLE("CKX-06"),
        INTERNAL("CKX-07");

        /** Message affiché : code et cause. */
        fun message(detail: String = ""): String {
            val text = when (this) {
                NETWORK -> tr("cookomix.com injoignable")
                HTTP -> tr("cookomix.com refuse ou n'arrive pas à répondre")
                LIST_UNREADABLE -> tr("liste des recettes illisible (le site a peut-être changé)")
                LIST_SHRUNK -> tr("liste des recettes anormalement courte")
                PAGES_UNREADABLE -> tr("pages de recettes illisibles (le site a peut-être changé de format)")
                TOO_MANY_INCOMPATIBLE -> tr("trop de recettes refusées d'un coup (le format des réglages a peut-être changé)")
                INTERNAL -> tr("erreur inattendue")
            }
            return "$code : $text" + if (detail.isBlank()) "" else " ($detail)"
        }
    }

    // ------------------------------------------------------------------ coupe-circuits

    /**
     * Liste du site suspecte : vide, ou moins de la moitié des recettes connues (page de maintenance,
     * sitemap tronqué…). La synchro s'arrête alors sans rien supprimer.
     */
    fun listLooksWrong(known: Int, now: Int): Boolean = now == 0 || (known >= 100 && now * 2 < known)

    /**
     * Surveille les pages traitées pendant une synchro. Normalement aucune page n'est illisible et
     * presque aucune recette n'est incompatible : au-delà de ces seuils, le site a sans doute changé
     * et continuer ferait disparaître des recettes. [verdict] dit quand s'arrêter.
     */
    class Breaker(
        private val minPages: Int = 20,
        private val unreadableInARow: Int = 10,
        private val unreadablePercent: Int = 30,
        private val incompatiblePercent: Int = 60,
    ) {
        var pages = 0
            private set
        private var unreadable = 0
        private var incompatible = 0
        private var row = 0

        fun record(c: Compat) {
            pages++
            if (c == Compat.UNREADABLE) {
                unreadable++
                row++
            } else row = 0
            if (c == Compat.INCOMPATIBLE) incompatible++
        }

        fun verdict(): Error? = when {
            row >= unreadableInARow -> Error.PAGES_UNREADABLE
            pages < minPages -> null
            unreadable * 100 >= pages * unreadablePercent -> Error.PAGES_UNREADABLE
            incompatible * 100 >= pages * incompatiblePercent -> Error.TOO_MANY_INCOMPATIBLE
            else -> null
        }
    }

    // ------------------------------------------------------------------ sitemaps

    class Entry(val url: String, val lastmod: String) {
        val slug: String get() = slugOf(url)
    }

    fun slugOf(url: String): String = url.trimEnd('/').substringAfterLast('/')

    /** Sitemaps des recettes listés dans l'index (recipe-sitemap1.xml, recipe-sitemap2.xml…). */
    fun recipeSitemaps(indexXml: String): List<String> =
        Regex("<loc>\\s*([^<\\s]+)\\s*</loc>").findAll(indexXml).map { it.groupValues[1] }
            .filter { Regex("/recipe-sitemap\\d*\\.xml$").containsMatchIn(it) }.toList()

    /** Recettes d'un sitemap (URL /recettes/<slug>/ + date de mise à jour). */
    fun parseSitemap(xml: String): List<Entry> =
        Regex("<url>(.*?)</url>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val loc = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!loc.contains("/recettes/") || loc.trimEnd('/').endsWith("/recettes")) return@mapNotNull null
            val mod = Regex("<lastmod>\\s*([^<\\s]+)\\s*</lastmod>").find(block)?.groupValues?.get(1).orEmpty()
            Entry(loc, mod)
        }.toList()

    // ------------------------------------------------------------------ page recette

    class Result(val recipe: Recipe?, val compat: Compat, val reasons: List<String>)

    /** Page HTML d'une recette → recette Toque et verdict de compatibilité. Ne lève jamais d'exception. */
    fun parsePage(html: String, url: String): Result = try {
        parsePageOrThrow(html, url)
    } catch (e: Exception) {
        unreadable(tr("structure inattendue : %s", e.javaClass.simpleName))
    }

    private fun unreadable(reason: String) = Result(null, Compat.UNREADABLE, listOf(reason))

    /** Réglage robot lisible : durée en secondes (nombre), vitesse et température présentes. */
    private fun wellFormedMix(m: Map<String, Any?>): Boolean {
        val d = m["mix_duration"]?.toString()?.trim()?.toDoubleOrNull() ?: return false
        return d in 0.0..MAX_SOURCE_S && !m["mix_speed"]?.toString().isNullOrBlank() && m["mix_temp"] != null
    }

    private fun parsePageOrThrow(html: String, url: String): Result {
        val ld = jsonLdRecipe(html) ?: return unreadable(tr("page sans recette"))
        val sbs = stepByStep(html) ?: return unreadable(tr("pas de pas-à-pas"))
        val title = cleanTitle(McOnline.plain(sbs.str("title")) ?: McOnline.plain(ld.str("name")).orEmpty())
        if (title.isEmpty()) return unreadable(tr("recette sans titre"))
        val rawSteps = sbs["steps"].list().map { it.obj() }
        if (rawSteps.isEmpty() || rawSteps.any { it.str("action").isNullOrEmpty() }) return unreadable(tr("étapes au format inattendu"))
        // Un réglage robot doit avoir sa durée et sa vitesse : sinon c'est le format qui a changé.
        if (rawSteps.any { it.str("action") == "mix" && !wellFormedMix(it) })
            return unreadable(tr("réglages au format inattendu"))

        val conv = Converter()
        val steps = conv.convert(rawSteps)
        if (conv.compat == Compat.INCOMPATIBLE) return Result(null, Compat.INCOMPATIBLE, conv.reasons)
        if (steps.none { it.machine != null }) return Result(null, Compat.INCOMPATIBLE, listOf(tr("aucun réglage robot")))

        val postId = Regex("\"currentPage\"\\s*:\\s*\\{\"type\":\"Recipe\",\"id\":(\\d+)").find(html)?.groupValues?.get(1)
        val id = ID_PREFIX + (postId ?: slugOf(url))
        val prep = minutes(ld.str("prepTime"))
        val cook = minutes(ld.str("cookTime"))
        val rating = ld["aggregateRating"].obj()
        val image = when (val img = ld["image"]) {
            is String -> img
            is List<*> -> img.firstOrNull() as? String
            is Map<*, *> -> img.obj().str("url")
            else -> null
        }.orEmpty().trim()

        val notes = ArrayList<String>()
        if (conv.compat == Compat.ADAPTED) {
            notes += tr("Recette Thermomix adaptée au Monsieur Cuisine connect :") + "\n" +
                conv.reasons.distinct().joinToString("\n") { "• $it" }
        }
        McOnline.plain(ld.str("description"))?.let { notes += it }
        notes += tr("Source : %s", url)

        val tags = ArrayList<String>()
        tags += TAG
        if (conv.compat == Compat.ADAPTED) tags += TAG_ADAPTED
        ld.str("recipeCuisine")?.trim()?.takeIf { it.isNotEmpty() }?.let { tags += it }

        val recipe = Recipe(
            id = id,
            title = title,
            category = ld.str("recipeCategory")?.trim().orEmpty(),
            tags = tags.distinct(),
            servings = Regex("\\d+").find(ld.str("recipeYield") ?: ld.int("recipeYield")?.toString().orEmpty())?.value?.toIntOrNull() ?: 0,
            prepMinutes = prep,
            // Sur Cookomix, cookTime est en pratique la durée totale.
            totalMinutes = maxOf(prep, cook),
            difficulty = 1,
            ingredients = ingredients(ld["recipeIngredient"].list().mapNotNull { it as? String }),
            steps = steps,
            source = RecipeSource.IMPORTED,
            image = image,
            notes = notes.joinToString("\n\n"),
            author = ld["author"].obj().str("name")?.trim().orEmpty(),
            rating = (rating.double("ratingValue") ?: 0.0).coerceIn(0.0, 5.0),
            ratingCount = (rating.int("ratingCount") ?: 0).coerceAtLeast(0),
        )
        return Result(recipe, conv.compat, conv.reasons.distinct())
    }

    private fun jsonLdRecipe(html: String): Map<String, Any?>? {
        for (m in Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val root = try {
                Json.parse(m.groupValues[1])
            } catch (_: Exception) {
                continue
            }
            val candidates = when (root) {
                is List<*> -> root
                is Map<*, *> -> root.obj()["@graph"]?.list()?.ifEmpty { null } ?: listOf(root)
                else -> emptyList()
            }
            for (c in candidates) {
                val o = c.obj()
                val type = o["@type"]
                if (type == "Recipe" || (type is List<*> && "Recipe" in type) || o.containsKey("recipeIngredient")) return o
            }
        }
        return null
    }

    private fun stepByStep(html: String): Map<String, Any?>? {
        val start = Regex("var\\s+stepbystep\\s*=\\s*").find(html) ?: return null
        val json = balancedObject(html, start.range.last + 1) ?: return null
        return try {
            Json.parse(json).obj()
        } catch (_: Exception) {
            null
        }
    }

    /** Objet JSON qui commence à [from] (accolades équilibrées, chaînes comprises). */
    private fun balancedObject(s: String, from: Int): String? {
        if (from >= s.length || s[from] != '{') return null
        var depth = 0
        var inString = false
        var i = from
        while (i < s.length) {
            val ch = s[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return s.substring(from, i + 1)
            }
            i++
        }
        return null
    }

    /** « PT1H20M » → 80. */
    fun minutes(iso: String?): Int {
        if (iso.isNullOrBlank()) return 0
        val h = Regex("(\\d+)H").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)M").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("(?i)\\s+(au|avec le|pour)\\s+thermomix\\s*$"), "").trim()

    /** « Parmesan - 40 grammes » → Ingredient(40, g, Parmesan). */
    fun ingredients(lines: List<String>): List<IngredientGroup> {
        val items = lines.mapNotNull { raw ->
            val line = McOnline.plain(raw)?.trim() ?: return@mapNotNull null
            val cut = line.lastIndexOf(" - ")
            if (cut < 0) return@mapNotNull Ingredient(name = line)
            val name = line.substring(0, cut).trim()
            val rest = line.substring(cut + 3).trim()
            val qty = Regex("^[0-9]+(?:[.,][0-9]+)?|^[½¼¾⅓⅔]").find(rest)?.value.orEmpty()
            val unit = rest.removePrefix(qty).trim()
            Ingredient(qty = qty.removeSuffix(".0"), unit = shortUnit(unit), name = name.ifEmpty { rest })
        }
        return if (items.isEmpty()) emptyList() else listOf(IngredientGroup("", items))
    }

    private fun shortUnit(u: String): String = when (u.lowercase()) {
        "gramme", "grammes" -> "g"
        "kilogramme", "kilogrammes" -> "kg"
        "millilitre", "millilitres" -> "ml"
        "centilitre", "centilitres" -> "cl"
        "litre", "litres" -> "l"
        "cuillère à soupe", "cuillères à soupe" -> "c. à soupe"
        "cuillère à café", "cuillères à café" -> "c. à café"
        else -> u
    }

    /** Texte du site adapté au robot : le Thermomix devient « le robot », le Varoma « le panier vapeur ». */
    fun adaptText(raw: String?): String {
        val plain = McOnline.plain(raw?.replace(Regex("<timer[^>]*/>"), "")) ?: return ""
        return plain
            .replace(Regex("(?i)\\bdu Thermomix\\b"), "du robot")
            .replace(Regex("(?i)\\bdans le Thermomix\\b"), "dans le bol")
            .replace(Regex("(?i)\\ble Thermomix\\b"), "le robot")
            .replace(Regex("(?i)\\bau Thermomix\\b"), "au robot")
            .replace(Regex("(?i)\\bThermomix\\b"), "robot")
            .replace(Regex("(?i)\\ble Varoma\\b"), "le panier vapeur")
            .replace(Regex("(?i)\\bdu Varoma\\b"), "du panier vapeur")
            .replace(Regex("(?i)\\bVaroma\\b"), "panier vapeur")
            .replace(Regex("(?i)\\bfouet papillon\\b"), "fouet")
            .replace(Regex("(?i)\\bpapillon\\b"), "fouet")
            .trim()
    }

    // ------------------------------------------------------------------ conversion Thermomix → MCC

    private class Converter {
        var compat = Compat.OK
        val reasons = ArrayList<String>()
        private val out = ArrayList<Step>()
        /** Gestes sans réglage (couvercle, accessoire…) rattachés à l'étape robot suivante. */
        private val pending = ArrayList<String>()
        private var accessory = Accessory.BLADE
        private var preparation = ""

        /** Accessoire en place après ce texte, s'il dit de mettre ou de retirer le fouet (sinon null). */
        private fun whiskAfter(desc: String): Accessory? {
            val d = desc.lowercase()
            // Le mot « fouet » seul : « fouetter », « crème fouettée » ne parlent pas de l'accessoire.
            val whisk = WHISK_WORD.findAll(d).lastOrNull() ?: return null
            // Le verbe le plus proche avant « fouet » décide (« Retirer le gobelet et insérer le fouet »).
            val before = d.substring(0, whisk.range.first)
            val removed = REMOVE_VERBS.findAll(before).lastOrNull()?.range?.first ?: -1
            val inserted = INSERT_VERBS.findAll(before).lastOrNull()?.range?.first ?: -1
            return when {
                removed > inserted -> Accessory.BLADE
                inserted > removed -> Accessory.WHISK
                REMOVE_VERBS.containsMatchIn(d) -> Accessory.BLADE
                INSERT_VERBS.containsMatchIn(d) -> Accessory.WHISK
                else -> null
            }
        }

        fun adapted(reason: String) {
            if (compat == Compat.OK) compat = Compat.ADAPTED
            reasons += reason
        }

        fun incompatible(reason: String) {
            compat = Compat.INCOMPATIBLE
            reasons += reason
        }

        fun convert(raw: List<Map<String, Any?>>): List<Step> {
            for (s in raw) {
                if (compat == Compat.INCOMPATIBLE) break
                val prep = s.str("preparation")?.trim().orEmpty()
                val header = if (prep.isNotEmpty() && prep != preparation) {
                    preparation = prep
                    "$prep : "
                } else ""
                val desc = adaptText(s.str("action_desc"))
                // Le fouet compte pour la sécurité (vitesse 4 au plus, pas de Turbo) : on le suit aussi
                // quand une étape ordinaire le met ou le retire (« Insérer le fouet », « Retirer le fouet »).
                val action = s.str("action")
                val whisk = if (action != "add-accessory" && action != "remove-accessory") whiskAfter(desc) else null
                // Un réglage dont le texte dit de retirer le fouet tourne encore avec lui.
                if (whisk != null && !(action == "mix" && whisk == Accessory.BLADE)) accessory = whisk
                when (action) {
                    "mix" -> {
                        mix(s, header + pending.joinToString(" ").let { if (it.isEmpty()) "" else "$it " })
                        if (whisk == Accessory.BLADE) accessory = Accessory.BLADE
                    }
                    "addtop", "addtop-w", "addtop-basket" -> if (desc.isNotEmpty()) pending += sentence(header + desc)
                    "add-accessory" -> {
                        val key = s.str("addaccessory")?.trim()?.lowercase().orEmpty()
                        when {
                            key in WHISK_KEYS || WHISK_WORD.containsMatchIn(desc.lowercase()) -> accessory = Accessory.WHISK
                            key in NEUTRAL_ACCESSORIES -> {}
                            // Accessoire inconnu : impossible de savoir s'il limite la vitesse, on refuse la recette.
                            else -> incompatible(tr("accessoire « %s » inconnu", key.ifEmpty { desc }))
                        }
                        flush()
                        if (desc.isNotEmpty()) out += Step(header + desc)
                    }
                    "remove-accessory" -> {
                        // Le fouet retiré, la lame redevient l'accessoire (Varoma et panier n'y changent rien).
                        val d = desc.lowercase()
                        if (WHISK_WORD.containsMatchIn(d) || ("vapeur" !in d && "panier" !in d)) accessory = Accessory.BLADE
                        flush()
                        if (desc.isNotEmpty()) out += Step(header + desc)
                    }
                    "add" -> {
                        flush()
                        val verb = s.str("action_verb")?.trim().orEmpty()
                        val text = header + listOf(verb, desc).filter { it.isNotEmpty() }.joinToString(" ")
                        if (text.isBlank()) continue
                        val grams = (s["action_extra"] as? Number)?.toInt()
                            ?: (s["action_extra"] as? String)?.trim()?.toIntOrNull()
                        val weigh = if (s.str("abbreviation") == "g" && grams != null && grams in 1..3000)
                            Weigh(desc.take(40), grams) else null
                        out += Step(sentence(text), weigh = weigh)
                    }
                    else -> {
                        flush()
                        if (desc.isNotEmpty()) out += Step(sentence(header + desc))
                    }
                }
            }
            flush()
            return out
        }

        private fun flush() {
            if (pending.isEmpty()) return
            out += Step(pending.joinToString(" "))
            pending.clear()
        }

        private fun sentence(t: String): String {
            val s = t.trim()
            if (s.isEmpty()) return s
            val first = s.substring(0, 1).uppercase() + s.substring(1)
            return if (first.last() in ".!?:)") first else "$first."
        }

        private fun mix(s: Map<String, Any?>, prefix: String) {
            pending.clear()
            val verb = s.str("action_verb")?.trim().orEmpty().ifEmpty { "Mélanger" }
            val seconds = s.str("mix_duration")?.trim()?.toDoubleOrNull()?.toInt() ?: (s.int("mix_duration") ?: 0)
            val speedRaw = s.str("mix_speed")?.trim()?.lowercase() ?: s.int("mix_speed")?.toString().orEmpty()
            val tempRaw = s.str("mix_temp")?.trim()?.lowercase() ?: s.int("mix_temp")?.toString().orEmpty()
            val reverse = s.str("direction")?.lowercase() == "inverse"
            val tmLabel = tmSummary(seconds, tempRaw, speedRaw, reverse)
            val extra = adaptText(s.str("action_desc")).let { if (it.isEmpty()) "" else " $it" }
            // Le réglage MCC s'affiche à part ; le texte ne cite le réglage Thermomix que s'il a été modifié.
            val base = sentence(prefix + verb.replaceFirstChar { it.uppercase() }) + extra
            var changed = false
            val original = " " + tr("(Thermomix : %s)", tmLabel)
            var text = base

            if (seconds <= 0) {
                out += Step(base + original)
                return
            }

            // Pétrin → programme Pétrir (2 min au plus par lancement : découpé).
            if (speedRaw == "pétrin" || speedRaw == "petrin" || speedRaw.startsWith("épi") || speedRaw == "knead") {
                var left = seconds
                var first = true
                if (seconds > MAX_KNEAD_S) {
                    adapted(tr("pétrissage de %s découpé en tranches de 2 min (limite du MCC)", duration(seconds)))
                    text += original
                }
                while (left > 0) {
                    val chunk = minOf(left, MAX_KNEAD_S)
                    val m = StepMachine(program = StepProgram.KNEAD, seconds = chunk)
                    out += Step(if (first) text else tr("Continuer à pétrir."), m, needsReview = true)
                    first = false
                    left -= chunk
                }
                return
            }
            if (speedRaw == "turbo") {
                out += Step(text, StepMachine(program = StepProgram.TURBO, seconds = seconds, pulses = maxOf(1, seconds), accessory = accessory), needsReview = true)
                return
            }

            val speedF = speedRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
            if (speedF == null) {
                incompatible(tr("vitesse « %s » sans équivalent", speedRaw))
                return
            }
            if (speedF > 10) {
                incompatible(tr("vitesse « %s » sans équivalent", speedRaw))
                return
            }
            // Vitesse cuillère (codée 0 par Cookomix, 40 tr/min sur le Thermomix) ou 0,5 → vitesse 1,
            // la plus lente du MCC (0 l'arrête) ; les recettes Lidl mijotent aussi en vitesse 1 inverse.
            var speed = Math.ceil(speedF).toInt().coerceIn(1, 10)
            if (speed.toDouble() != speedF) {
                // Demi-vitesse arrondie au-dessus : signalée (la cuillère → 1 est l'équivalence documentée).
                if (speedF >= 1) adapted(tr("vitesse %s arrondie à %d", speedRaw.replace('.', ','), speed))
                changed = true
            }

            var tempC: Int
            when {
                tempRaw.isEmpty() || tempRaw == "0" -> tempC = 0
                // « Varoma » = 120 °C au plus sur le Thermomix, en gardant sa vitesse. On reste en manuel
                // à 120 °C : le programme Vapeur du MCC arrête la lame et s'arrête à 60 min (notice SKMC 1200).
                tempRaw == "varoma" -> {
                    tempC = 120
                    changed = true
                }
                else -> {
                    val t = tempRaw.replace(',', '.').toDoubleOrNull()?.toInt()
                    if (t == null) {
                        incompatible(tr("température « %s » sans équivalent", tempRaw))
                        return
                    }
                    if (t > MAX_TEMP_C) {
                        incompatible(tr("%d °C dépasse les 130 °C du MCC", t))
                        return
                    }
                    tempC = if (t in 1..36) 37 else t
                    val level = Mcc.levelAtMost(tempC)
                    val mccC = Mcc.celsiusForLevel(level)
                    if (mccC != tempC) {
                        adapted(tr("%d °C arrondi à %d °C", tempC, mccC))
                        tempC = mccC
                        changed = true
                    }
                }
            }

            if (tempC > 0 && speed > Limits.MAX_SPEED_HEATED) {
                adapted(tr("vitesse %s en chauffe ramenée à 3 (limite du MCC)", speedRaw))
                speed = Limits.MAX_SPEED_HEATED
                changed = true
            }
            if (reverse && speed > Limits.MAX_SPEED_REVERSE) {
                adapted(tr("vitesse %s en sens inverse ramenée à 3 (limite du MCC)", speedRaw))
                speed = Limits.MAX_SPEED_REVERSE
                changed = true
            }
            if (accessory == Accessory.WHISK && speed > Accessory.WHISK.maxSpeed) {
                adapted(tr("vitesse %s avec le fouet ramenée à 4 (limite du MCC)", speedRaw))
                speed = Accessory.WHISK.maxSpeed
                changed = true
            }
            if (speed > 6 && seconds > MAX_HIGH_SPEED_S) {
                incompatible(tr("vitesse %d pendant plus de 15 min", speed))
                return
            }
            if (changed) text += original
            splitDuration(seconds, MAX_MANUAL_S, text) { sec ->
                StepMachine(program = StepProgram.MANUAL, seconds = sec, speed = speed, tempC = tempC, reverse = reverse, accessory = accessory)
            }
        }

        private fun splitDuration(seconds: Int, max: Int, text: String, make: (Int) -> StepMachine) {
            if (seconds > max) adapted(tr("cuisson de %s découpée (99 min au plus par lancement)", duration(seconds)))
            var left = seconds
            var first = true
            while (left > 0) {
                val chunk = minOf(left, max)
                val m = make(chunk)
                val problems = m.violations()
                if (problems.isNotEmpty()) {
                    incompatible(problems.first().message)
                    return
                }
                out += Step(if (first) text else tr("Continuer la cuisson."), m, needsReview = true)
                first = false
                left -= chunk
            }
        }
    }

    private fun duration(seconds: Int): String = SafetyPolicy.formatDuration(seconds * 1000L)

    /** Réglage tel qu'écrit pour le Thermomix (affiché dans le texte de l'étape). */
    private fun tmSummary(seconds: Int, temp: String, speed: String, reverse: Boolean): String = buildList {
        add(duration(seconds))
        when {
            temp == "varoma" -> add(tr("vapeur"))
            temp.isNotEmpty() && temp != "0" -> add("$temp °C")
        }
        if (reverse) add(tr("sens inverse"))
        add(when {
            speed == "0" -> tr("vitesse cuillère")
            speed.toDoubleOrNull() != null -> tr("vitesse %s", speed.replace('.', ','))
            else -> speed
        })
    }.joinToString(" / ")
}
