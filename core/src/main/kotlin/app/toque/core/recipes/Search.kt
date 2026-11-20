package app.toque.core.recipes

import java.text.Normalizer

object TextNorm {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val STOP = hashSetOf(
        "de", "du", "des", "la", "le", "les", "l", "d", "et", "a", "au", "aux", "en", "un", "une",
        "pour", "sur", "par", "ou", "son", "sa", "ses", "mon", "ma", "mes", "the", "of", "with", "y",
        "recette", "recettes",
    )

    fun normalize(s: String): String {
        var t = s.lowercase().replace("œ", "oe").replace("æ", "ae").replace("ß", "ss")
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
        t = MARKS.replace(t, "")
        t = NON_ALNUM.replace(t, " ")
        return t.trim()
    }

    /** Pluriels français simples : pommes -> pomme, choux -> chou, gâteaux -> gateau. */
    fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("aux") -> w.dropLast(1)
        w.length > 3 && (w.endsWith("s") || w.endsWith("x")) && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun tokens(s: String, keepStopWords: Boolean = false): List<String> {
        val n = normalize(s)
        if (n.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (w in n.split(' ')) {
            if (w.isEmpty()) continue
            if (!keepStopWords && w in STOP) continue
            out += stem(w)
        }
        return out
    }

    /** Distance de Damerau-Levenshtein (transpositions adjacentes) bornée par [max]. */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }
}

/** Provenance d'une recette : site Lidl (ou appli d'origine) ou cookomix.com. */
enum class Origin {
    ALL, LIDL, COOKOMIX;

    fun accepts(id: String): Boolean = when (this) {
        ALL -> true
        LIDL -> !id.startsWith(Cookomix.ID_PREFIX)
        COOKOMIX -> id.startsWith(Cookomix.ID_PREFIX)
    }

    companion object {
        fun of(name: String?): Origin = values().firstOrNull { it.name == name } ?: ALL
    }
}

/** Filtres de la liste de recettes. */
data class RecipeFilter(
    val category: String = "",
    val favoritesOnly: Boolean = false,
    val source: RecipeSource? = null,
    val maxMinutes: Int = 0,
    val origin: Origin = Origin.ALL,
)

/**
 * Index de recherche en mémoire : tolérant aux accents, aux pluriels et aux fautes de frappe,
 * pondéré (titre > catégorie/tags > ingrédients > étapes). Quelques millisecondes pour
 * des milliers de recettes, même sur la tablette d'origine.
 */
class SearchIndex private constructor(
    val entries: List<Entry>,
    private val vocabulary: Array<String>,
    /** terme -> documents triés, un entier par document (voir [pack]). */
    private val postings: HashMap<String, IntArray>,
) {
    constructor(recipes: List<Recipe>) : this(Builder().apply { for (r in recipes) add(r) })

    private constructor(b: Builder) : this(b.entries, b.vocabulary(), b.postings())

    data class Entry(
        val id: String,
        val title: String,
        val category: String,
        val tags: List<String>,
        val favorite: Boolean,
        val source: RecipeSource,
        val totalMinutes: Int,
        val image: String,
        val machineSteps: Int,
        val normTitle: String,
        val rating: Double = 0.0,
        val ratingCount: Int = 0,
    )

    data class Hit(val entry: Entry, val score: Double, val matched: Int)

    /**
     * Construit l'index recette par recette, sans garder les recettes en mémoire (le catalogue
     * complet fait plusieurs milliers de fiches sur une tablette de 1 Go).
     */
    class Builder {
        internal val entries = ArrayList<Entry>()
        private val lists = HashMap<String, Ints>()

        fun add(r: Recipe) {
            val index = entries.size
            // Cookomix donne parfois plusieurs catégories (« Petit déjeuner, Dessert ») : la première
            // sert de catégorie, les autres restent trouvables à la recherche.
            val category = if (r.id.startsWith(Cookomix.ID_PREFIX)) r.category.split(',').first().trim() else r.category
            entries += Entry(r.id, r.title, category, r.tags, r.favorite, r.source, r.totalMinutes, r.image,
                r.machineSteps, TextNorm.normalize(r.title), r.rating, r.ratingCount)
            fun add(text: String, w: Int) {
                for (t in TextNorm.tokens(text)) lists.getOrPut(t) { Ints() }.put(index, w)
            }
            add(r.title, W_TITLE)
            add(r.category, W_TAG)
            for (t in r.tags) add(t, W_TAG)
            for (i in r.allIngredients) add(i.name, W_INGREDIENT)
            for (s in r.steps) add(s.text, W_STEP)
        }

        fun build(): SearchIndex = SearchIndex(this)

        internal fun vocabulary(): Array<String> = lists.keys.toTypedArray().also { java.util.Arrays.sort(it) }

        internal fun postings(): HashMap<String, IntArray> {
            val out = HashMap<String, IntArray>(lists.size * 2)
            for ((term, l) in lists) out[term] = l.toArray()
            return out
        }

        /** Liste d'entiers extensible ; les documents arrivent dans l'ordre, le dernier est le courant. */
        private class Ints {
            private var a = IntArray(4)
            private var n = 0

            /** Ajoute le document, ou relève son poids s'il est déjà le dernier de la liste. */
            fun put(doc: Int, weight: Int) {
                if (n > 0 && docOf(a[n - 1]) == doc) {
                    if (weight > weightOf(a[n - 1])) a[n - 1] = pack(doc, weight)
                    return
                }
                if (n == a.size) a = a.copyOf(n * 2)
                a[n++] = pack(doc, weight)
            }

            fun toArray(): IntArray = a.copyOf(n)
        }
    }

    /** Même index avec les favoris donnés (instantané : ne relit aucune recette). */
    fun withFavorites(ids: Set<String>): SearchIndex {
        if (entries.none { it.favorite != (it.id in ids) }) return this
        return SearchIndex(entries.map { e -> (e.id in ids).let { f -> if (e.favorite == f) e else e.copy(favorite = f) } },
            vocabulary, postings)
    }

    /**
     * Écrit l'index (sans les favoris) pour le recharger au prochain démarrage sans relire toutes
     * les recettes. Format interne, relu par [read] ; [FORMAT] change quand le découpage en mots
     * ou les poids changent.
     */
    fun write(out: java.io.DataOutputStream) {
        fun str(v: String) {
            val b = v.toByteArray(Charsets.UTF_8)
            out.writeInt(b.size)
            out.write(b)
        }
        out.writeInt(FORMAT)
        out.writeInt(entries.size)
        for (e in entries) {
            str(e.id); str(e.title); str(e.category)
            out.writeInt(e.tags.size)
            for (t in e.tags) str(t)
            str(e.source.name)
            out.writeInt(e.totalMinutes)
            str(e.image)
            out.writeInt(e.machineSteps)
            str(e.normTitle)
            out.writeDouble(e.rating)
            out.writeInt(e.ratingCount)
        }
        out.writeInt(vocabulary.size)
        for (t in vocabulary) {
            val p = postings.getValue(t)
            str(t)
            out.writeInt(p.size)
            for (v in p) out.writeInt(v)
        }
    }

    companion object {
        /** Version du format de [write] (et des règles d'indexation). */
        const val FORMAT = 1
        private const val W_TITLE = 10
        private const val W_TAG = 5
        private const val W_INGREDIENT = 4
        private const val W_STEP = 1

        /** Un document et son poids dans un seul entier : l'index d'entrée, puis le poids sur 4 bits. */
        private const val WEIGHT_BITS = 4
        private const val WEIGHT_MASK = (1 shl WEIGHT_BITS) - 1
        private fun pack(doc: Int, weight: Int): Int = (doc shl WEIGHT_BITS) or weight
        private fun docOf(packed: Int): Int = packed ushr WEIGHT_BITS
        private fun weightOf(packed: Int): Int = packed and WEIGHT_MASK

        /**
         * Relit un index écrit par [write], sans favoris (voir [withFavorites]).
         * Lève une exception si les données sont d'un autre format, tronquées ou incohérentes
         * (l'appelant reconstruit alors l'index depuis les recettes).
         */
        fun read(buf: java.nio.ByteBuffer): SearchIndex {
            fun count(max: Int): Int {
                val n = buf.int
                require(n in 0..max) { "taille invalide : $n" }
                return n
            }
            fun str(): String {
                val b = ByteArray(count(buf.remaining()))
                buf.get(b)
                return String(b, Charsets.UTF_8)
            }
            require(buf.int == FORMAT) { "format d'index inconnu" }
            val n = count(buf.remaining())
            val entries = ArrayList<Entry>(n)
            repeat(n) {
                val id = str()
                val title = str()
                val category = str()
                val tags = List(count(buf.remaining())) { str() }
                val source = RecipeSource.valueOf(str())
                val minutes = buf.int
                val image = str()
                val machineSteps = buf.int
                val normTitle = str()
                val rating = buf.double
                val ratingCount = buf.int
                entries += Entry(id, title, category, tags, false, source, minutes, image, machineSteps,
                    normTitle, rating, ratingCount)
            }
            val terms = count(buf.remaining())
            val vocabulary = ArrayList<String>(terms)
            val postings = HashMap<String, IntArray>(terms * 2)
            repeat(terms) {
                val term = str()
                val p = IntArray(count(buf.remaining() / 4))
                for (i in p.indices) {
                    val v = buf.int
                    require(docOf(v) < n) { "document hors index" }
                    p[i] = v
                }
                vocabulary += term
                postings[term] = p
            }
            require(!buf.hasRemaining()) { "données en trop" }
            val sorted = vocabulary.toTypedArray()
            for (i in 1 until sorted.size) require(sorted[i - 1] < sorted[i]) { "vocabulaire non trié" }
            return SearchIndex(entries, sorted, postings)
        }
    }

    /** Une catégorie et son nombre de recettes, par source (Lidl / Cookomix). */
    class CategoryStat(val name: String, val lidl: Int, val cookomix: Int) {
        val total: Int get() = lidl + cookomix
    }

    /** Catégories de la plus fournie à la moins fournie (à égalité : ordre alphabétique). */
    val categoryStats: List<CategoryStat> by lazy {
        val lidl = HashMap<String, Int>()
        val ckx = HashMap<String, Int>()
        for (e in entries) {
            if (e.category.isBlank()) continue
            val m = if (e.id.startsWith(Cookomix.ID_PREFIX)) ckx else lidl
            m[e.category] = (m[e.category] ?: 0) + 1
        }
        (lidl.keys + ckx.keys).map { CategoryStat(it, lidl[it] ?: 0, ckx[it] ?: 0) }
            .sortedWith(compareByDescending<CategoryStat> { it.total }.thenBy { TextNorm.normalize(it.name) })
    }

    /** Catégories dont le nom contient [query] (accents et casse ignorés), ayant des recettes de [origin]. */
    fun findCategories(query: String, origin: Origin = Origin.ALL): List<CategoryStat> {
        val q = TextNorm.normalize(query).trim()
        return categoryStats.filter { s ->
            (q.isEmpty() || TextNorm.normalize(s.name).contains(q)) && when (origin) {
                Origin.ALL -> true
                Origin.LIDL -> s.lidl > 0
                Origin.COOKOMIX -> s.cookomix > 0
            }
        }.let { l -> if (origin == Origin.ALL) l else l.sortedWith(compareByDescending<CategoryStat> { s -> if (origin == Origin.LIDL) s.lidl else s.cookomix }.thenBy { TextNorm.normalize(it.name) }) }
    }


    private fun prefixMatches(token: String): List<String> {
        var lo = java.util.Arrays.binarySearch(vocabulary, token)
        if (lo < 0) lo = -lo - 1
        val out = ArrayList<String>()
        var i = lo
        while (i < vocabulary.size && vocabulary[i].startsWith(token) && out.size < 64) {
            out += vocabulary[i]
            i++
        }
        return out
    }

    /** Termes candidats pour un mot de la requête, avec leur qualité de correspondance. */
    private fun candidates(token: String): Map<String, Double> {
        val c = HashMap<String, Double>()
        if (postings.containsKey(token)) c[token] = 1.0
        if (token.length >= 2) for (t in prefixMatches(token)) if (t != token) c[t] = maxOf(c[t] ?: 0.0, 0.75)
        if (token.length >= 4) {
            val max = if (token.length >= 8) 2 else 1
            for (t in vocabulary) {
                if (c.containsKey(t)) continue
                if (kotlin.math.abs(t.length - token.length) > max) continue
                if (t[0] != token[0] && token.length < 6) continue
                val d = TextNorm.distance(token, t, max)
                if (d <= max) c[t] = if (d == 1) 0.55 else 0.35
            }
        }
        return c
    }

    private fun accepts(e: Entry, f: RecipeFilter): Boolean {
        if (f.category.isNotEmpty() && e.category != f.category) return false
        if (!f.origin.accepts(e.id)) return false
        if (f.favoritesOnly && !e.favorite) return false
        if (f.source != null && e.source != f.source) return false
        if (f.maxMinutes > 0 && (e.totalMinutes <= 0 || e.totalMinutes > f.maxMinutes)) return false
        return true
    }

    fun search(query: String, filter: RecipeFilter = RecipeFilter()): List<Hit> {
        val tokens = TextNorm.tokens(query).distinct()
        if (tokens.isEmpty()) {
            return entries.filter { accepts(it, filter) }
                .sortedWith(compareByDescending<Entry> { it.favorite }.thenBy { it.normTitle })
                .map { Hit(it, 0.0, 0) }
        }
        val perToken = tokens.map { tok ->
            val scores = HashMap<Int, Double>()
            for ((term, quality) in candidates(tok)) {
                val p = postings[term] ?: continue
                for (v in p) {
                    val doc = docOf(v)
                    val s = quality * weightOf(v)
                    if (s > (scores[doc] ?: 0.0)) scores[doc] = s
                }
            }
            scores
        }
        val normQuery = TextNorm.normalize(query)
        val totals = HashMap<Int, Double>()
        val counts = HashMap<Int, Int>()
        for (scores in perToken) for ((doc, s) in scores) {
            totals[doc] = (totals[doc] ?: 0.0) + s
            counts[doc] = (counts[doc] ?: 0) + 1
        }
        val hits = ArrayList<Hit>()
        for ((doc, total) in totals) {
            val e = entries[doc]
            if (!accepts(e, filter)) continue
            val matched = counts[doc] ?: 0
            var score = total
            if (matched == tokens.size) score += 100.0
            if (e.normTitle.startsWith(normQuery)) score += 20.0 else if (e.normTitle.contains(normQuery)) score += 10.0
            if (e.favorite) score += 1.0
            hits += Hit(e, score, matched)
        }
        val full = hits.filter { it.matched == tokens.size }
        val result = if (full.isNotEmpty()) full else hits
        return result.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.entry.normTitle })
    }

    /** « Qu'est-ce que je peux faire avec… » : classe par nombre d'ingrédients présents. */
    fun withIngredients(list: List<String>, filter: RecipeFilter = RecipeFilter()): List<Hit> {
        val wanted = list.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        val counts = HashMap<Int, Int>()
        for (w in wanted) {
            val docs = HashSet<Int>()
            for (tok in TextNorm.tokens(w)) for ((term, q) in candidates(tok)) {
                if (q < 0.5) continue
                val p = postings[term] ?: continue
                for (v in p) if (weightOf(v) >= W_INGREDIENT) docs += docOf(v)
            }
            for (d in docs) counts[d] = (counts[d] ?: 0) + 1
        }
        return counts.filter { accepts(entries[it.key], filter) }
            .map { Hit(entries[it.key], it.value.toDouble(), it.value) }
            .sortedWith(compareByDescending<Hit> { it.matched }.thenBy { it.entry.normTitle })
    }
}
