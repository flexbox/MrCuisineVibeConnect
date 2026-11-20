package app.toque.recipes

import android.content.Context
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.recipes.Cookomix
import app.toque.core.recipes.Origin
import app.toque.core.recipes.Recipe
import app.toque.replaceAtomically
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recettes de cookomix.com compatibles avec le Monsieur Cuisine connect.
 *
 * Liste complète par les sitemaps du site (avec la date de mise à jour de chaque recette), puis
 * seules les pages nouvelles ou modifiées sont téléchargées et converties ([Cookomix.parsePage]).
 * Les recettes incompatibles sont mémorisées pour ne pas être retéléchargées tant qu'elles ne
 * changent pas. Requêtes espacées (plus d'une seconde) ; reprend où elle s'est arrêtée.
 *
 * Panne ou changement du site : la synchro s'arrête avec un code d'erreur ([Cookomix.Error],
 * docs/COOKOMIX-ERREURS.md) et ne supprime alors aucune recette. Une page illisible n'efface
 * jamais la version déjà enregistrée : elle sera relue à la synchro suivante.
 */
class CookomixSync(private val context: Context, private val prefs: Prefs, private val repo: RecipeRepo, private val log: Logbook) {
    private val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
    private val indexFile = File(context.filesDir, "recettes-cookomix.json")
    private val fetcher = Fetcher(version, repo)

    /**
     * Ligne d'index : date de mise à jour du sitemap, verdict, identifiant Toque, et version des
     * règles de conversion qui ont produit la recette (1 pour les lignes écrites avant ce champ).
     */
    private class Mark(val lastmod: String, val status: String, val id: String, val conversion: Int = Cookomix.CONVERSION) {
        override fun toString() = "$lastmod|$status|$id|$conversion"

        companion object {
            fun of(s: String?): Mark? {
                val p = s?.split('|') ?: return null
                return if (p.size < 3) null else Mark(p[0], p[1], p[2], p.getOrNull(3)?.toIntOrNull() ?: 1)
            }
        }
    }

    fun sync(progress: (String) -> Unit = {}): LidlSync.Report {
        if (!running.compareAndSet(false, true)) return LidlSync.Report(false, tr("Synchronisation déjà en cours"))
        prefs.cookomixSyncPending = true
        try {
            // Progression partagée : un écran Réglages rouvert pendant la synchro la retrouve.
            return finish(run { msg -> current = msg; progress(msg) })
        } catch (e: Exception) {
            log.crash("cookomix", e)
            return finish(failure(Cookomix.Error.INTERNAL, e.message ?: e.javaClass.simpleName))
        } finally {
            current = ""
            running.set(false)
        }
    }

    /** Échec codé : les recettes déjà enregistrées restent, et le message le dit. */
    private fun failure(error: Cookomix.Error, detail: String = ""): LidlSync.Report {
        log.write("cookomix", "${error.code} $detail")
        return LidlSync.Report(false, tr("Recettes Cookomix non mises à jour, %s. Les recettes déjà enregistrées restent disponibles.", error.message(detail)))
    }

    private fun networkFailure(e: IOException): LidlSync.Report =
        if (e is HttpStatusException) failure(Cookomix.Error.HTTP, tr("réponse %d du serveur", e.code))
        else failure(Cookomix.Error.NETWORK, e.message ?: e.javaClass.simpleName)

    private fun run(progress: (String) -> Unit): LidlSync.Report {
        val withAdapted = prefs.cookomixAdapted
        val index = HashMap(readIndex())
        val before = HashMap(index)

        // 1. Liste des recettes (sitemaps). Liste vide ou anormalement courte : on s'arrête sans rien toucher.
        progress(tr("Liste des recettes Cookomix…"))
        val entries = ArrayList<Cookomix.Entry>()
        try {
            val maps = Cookomix.recipeSitemaps(text(Cookomix.SITEMAP_INDEX))
            if (maps.isEmpty()) return failure(Cookomix.Error.LIST_UNREADABLE, tr("aucun sitemap de recettes"))
            for ((i, url) in maps.withIndex()) {
                progress(tr("Liste des recettes Cookomix… %d / %d", i + 1, maps.size))
                // Un sitemap vide n'annonce pas des recettes retirées : c'est le site qui répond de travers.
                val listed = Cookomix.parseSitemap(text(url))
                if (listed.isEmpty()) return failure(Cookomix.Error.LIST_UNREADABLE, tr("sitemap sans recette : %s", url.substringAfterLast('/')))
                entries += listed
                pause(600)
            }
        } catch (e: IOException) {
            return networkFailure(e)
        }
        if (entries.isEmpty()) return failure(Cookomix.Error.LIST_UNREADABLE, tr("aucune recette dans les sitemaps"))
        if (Cookomix.listLooksWrong(index.size, entries.size))
            return failure(Cookomix.Error.LIST_SHRUNK, tr("%d recettes au lieu de %d", entries.size, index.size))

        // 2. Pages nouvelles, modifiées ou converties avec d'anciennes règles (ou compatibles mais absentes du catalogue).
        val present = repo.ids()
        // Page illisible ou en échec : on garde la version déjà enregistrée (identifiant conservé).
        fun wanted(m: Mark) = m.status == OK || (m.status == ADAPTED && withAdapted) ||
            ((m.status == UNREADABLE || m.status == ERROR) && m.id.isNotEmpty())
        val todo = entries.filter { e ->
            val m = Mark.of(index[e.slug])
            m == null || m.lastmod != e.lastmod || m.conversion != Cookomix.CONVERSION || m.status == ERROR || m.status == UNREADABLE ||
                ((m.status == OK || m.status == ADAPTED) && wanted(m) && m.id !in present)
        }
        val batch = ArrayList<Recipe>()
        val breaker = Cookomix.Breaker()
        val touched = ArrayList<String>()
        var failures = 0
        var unreadable = 0
        var inARow = 0
        for ((i, e) in todo.withIndex()) {
            // Total du site et déjà traitées, pour qu'une reprise ne ressemble pas à une perte.
            progress(tr("Recettes Cookomix : %d / %d (déjà à jour : %d)", entries.size - todo.size + i + 1, entries.size, entries.size - todo.size))
            val previousId = Mark.of(index[e.slug])?.id.orEmpty()
            try {
                val res = Cookomix.parsePage(text(e.url), e.url)
                val r = res.recipe
                val status = when (res.compat) {
                    Cookomix.Compat.OK -> OK
                    Cookomix.Compat.ADAPTED -> ADAPTED
                    Cookomix.Compat.INCOMPATIBLE -> INCOMPATIBLE
                    Cookomix.Compat.UNREADABLE -> UNREADABLE
                }
                index[e.slug] = Mark(e.lastmod, status, r?.id ?: previousId).toString()
                touched += e.slug
                if (r != null && (status == OK || (status == ADAPTED && withAdapted))) {
                    batch += r
                    // Photo tout de suite (et non en fin de synchro) : la recette s'affiche avec elle.
                    photoNow(r.image)
                }
                when (status) {
                    INCOMPATIBLE -> log.write("cookomix", "${e.slug} : ${res.reasons.joinToString("; ")}")
                    UNREADABLE -> {
                        unreadable++
                        log.write("cookomix", "${Cookomix.Error.PAGES_UNREADABLE.code} ${e.slug} : ${res.reasons.joinToString("; ")}")
                    }
                }
                breaker.record(res.compat)
                inARow = 0
            } catch (ex: IOException) {
                failures++
                inARow++
                index[e.slug] = Mark(e.lastmod, ERROR, previousId).toString()
                log.write("cookomix", "${e.slug} : ${ex.message}")
                if (inARow >= 5) {
                    save(batch, index)
                    return networkFailure(ex)
                }
            }
            // Le site semble avoir changé : on annule les verdicts négatifs de cette synchro et on s'arrête.
            breaker.verdict()?.let { error ->
                for (slug in touched) {
                    val status = Mark.of(index[slug])?.status
                    if (status == INCOMPATIBLE || status == UNREADABLE) {
                        val old = before[slug]
                        if (old == null) index.remove(slug) else index[slug] = old
                    }
                }
                save(batch, index)
                return failure(error, tr("%d pages lues", breaker.pages))
            }
            if (batch.size >= 25) save(batch, index)
            pause(PAGE_PAUSE_MS)
        }
        save(batch, index)

        // 3. Le catalogue Cookomix suit le site et le réglage « recettes adaptées ».
        val slugs = entries.map { it.slug }.toHashSet()
        index.keys.retainAll(slugs)
        // Une recette adaptée mise en favori reste, même si l'option « recettes adaptées » est coupée.
        repo.awaitFavorites()
        val keep = index.values.mapNotNull { Mark.of(it) }
            .filter { it.id.isNotEmpty() && (wanted(it) || (it.status == ADAPTED && repo.isFavorite(it.id))) }
            .map { it.id }.toHashSet()
        repo.removeAllExcept(keep, rebuild = false, origin = Origin.COOKOMIX)
        writeIndex(index)
        repo.refresh()

        // 4. Photos manquantes.
        fetcher.photos(keep, 300, { log.write("cookomix", it) }, progress)

        val marks = index.values.mapNotNull { Mark.of(it) }
        val ok = marks.count { it.status == OK }
        val adapted = marks.count { it.status == ADAPTED }
        val ko = marks.count { it.status == INCOMPATIBLE }
        var note = if (failures > 0) " " + tr("(%d en échec, réessayées à la prochaine synchro)", failures) else ""
        if (unreadable > 0) note += " " + tr("(%d illisibles, code %s, réessayées à la prochaine synchro)", unreadable, Cookomix.Error.PAGES_UNREADABLE.code)
        return LidlSync.Report(true, tr("Cookomix : %d compatibles, %d adaptées, %d incompatibles", ok, adapted, ko) + note, keep.size)
    }

    /** Recettes enregistrées depuis le dernier rechargement de la liste. */
    private var unseen = 0
    private var shownOnce = false

    private fun save(batch: MutableList<Recipe>, index: Map<String, String>) {
        if (batch.isNotEmpty()) {
            unseen += batch.size
            // Les recettes apparaissent dans la liste pendant la synchro au lieu d'attendre la fin.
            // Premier affichage vite (100 recettes), puis tous les 500 : chaque rechargement relit tout
            // le catalogue, ce qui coûte en mémoire et en temps sur la tablette du robot.
            val every = if (shownOnce) REFRESH_EVERY else REFRESH_FIRST
            val refresh = unseen >= every
            repo.upsertAll(ArrayList(batch), rebuild = refresh)
            if (refresh) { unseen = 0; shownOnce = true }
        }
        batch.clear()
        writeIndex(index)
    }

    /** Photo d'une recette qui vient d'être lue ; un échec est rattrapé en fin de synchro. */
    private fun photoNow(url: String) {
        if (!url.startsWith("http") || repo.imageFile(url) != null) return
        try {
            fetcher.savePhoto(url)
        } catch (e: Exception) {
            // Une photo, quelle que soit l'erreur, ne doit pas arrêter la synchro des recettes.
            log.write("cookomix", "photo $url : $e")
        } catch (e: OutOfMemoryError) {
            log.write("cookomix", "photo $url : mémoire insuffisante")
        }
        pause(300)
    }

    /** Page publique du site. */
    private fun text(url: String): String =
        String(fetcher.download(url, mapOf("Accept-Language" to "fr-FR"), 4 * 1024 * 1024), Charsets.UTF_8)

    private fun readIndex(): Map<String, String> = try {
        Json.parse(indexFile.readText()).obj()["recipes"].obj().mapValues { (_, v) -> v?.toString().orEmpty() }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeIndex(index: Map<String, String>) {
        try {
            val json = Json.write(linkedMapOf("recipes" to index))
            indexFile.replaceAtomically { it.write(json.toByteArray()) }
        } catch (e: Exception) {
            log.write("cookomix", "index non enregistré : $e")
        }
    }

    private fun finish(r: LidlSync.Report): LidlSync.Report {
        // Terminée (réussie ou arrêtée sur une erreur) : pas de reprise automatique au démarrage.
        prefs.cookomixResumes = 0
        prefs.cookomixSyncPending = false
        val stamp = SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(Date())
        prefs.cookomixLastSyncMs = System.currentTimeMillis()
        prefs.cookomixLastResult = "$stamp : ${r.message}"
        log.write("cookomix", r.message)
        return r
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val OK = "ok"
        private const val ADAPTED = "adaptee"
        private const val INCOMPATIBLE = "incompatible"
        private const val ERROR = "erreur"
        private const val UNREADABLE = "illisible"
        /** Pause entre deux pages (plus le téléchargement et la photo) : une heure ou plus la première fois. */
        private const val PAGE_PAUSE_MS = 1_200L
        private const val REFRESH_FIRST = 100
        private const val REFRESH_EVERY = 500
        private val running = AtomicBoolean(false)

        val isRunning: Boolean get() = running.get()

        /** Dernier message de progression de la synchro en cours (vide sinon). */
        @Volatile var current: String = ""
            private set

    }
}
