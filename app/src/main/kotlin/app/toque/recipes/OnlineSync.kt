package app.toque.recipes

import android.content.Context
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Origin
import app.toque.core.recipes.Recipe
import app.toque.replaceAtomically
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recettes officielles du Monsieur Cuisine connect, téléchargées directement depuis
 * monsieur-cuisine.com (API publique, sans compte) : la liste complète, puis seulement les
 * fiches nouvelles ou modifiées, et enfin les photos (réduites pour l'écran du robot).
 * Requêtes espacées pour ne pas charger les serveurs. Reprend où elle s'est arrêtée.
 */
class OnlineSync(private val context: Context, private val prefs: Prefs, private val repo: RecipeRepo, private val log: Logbook) {
    private val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
    private val indexFile = File(context.filesDir, "recettes-en-ligne.json")
    private val fetcher = Fetcher(version, repo)

    fun sync(progress: (String) -> Unit = {}): LidlSync.Report {
        if (!running.compareAndSet(false, true)) return LidlSync.Report(false, tr("Synchronisation déjà en cours"))
        prefs.lidlSyncPending = true
        try {
            // Progression partagée : un écran Réglages rouvert pendant la synchro la retrouve.
            return finish(run { msg -> current = msg; progress(msg) })
        } catch (e: Exception) {
            log.crash("synchro", e)
            return finish(LidlSync.Report(false, tr("Synchronisation impossible : %s", e.message ?: e.toString())))
        } finally {
            current = ""
            running.set(false)
        }
    }

    private fun run(progress: (String) -> Unit): LidlSync.Report {
        val language = McOnline.languageFor(I18n.code)
        val stored = readIndex()
        val index = if (stored.first == language) HashMap(stored.second) else HashMap()

        // 1. Liste complète (résumés).
        progress(tr("Liste des recettes…"))
        val entries = ArrayList<McOnline.Entry>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            val p = try {
                McOnline.parsePage(get(McOnline.listUrl(page), language))
            } catch (e: IOException) {
                return LidlSync.Report(false, tr("Serveur de recettes injoignable : %s", e.message ?: e.toString()))
            }
            pages = p.totalPages.coerceIn(1, 1000)
            entries += p.entries
            progress(tr("Liste des recettes… %d / %d", page, pages))
            page++
            pause(300)
        }
        if (entries.isEmpty()) return LidlSync.Report(false, tr("Aucune recette reçue du serveur"))

        // 2. Fiches nouvelles ou modifiées ; pour les autres, seuls l'auteur et la note sont mis à jour.
        val present = repo.ids()
        val todo = entries.filter { e ->
            McOnline.updatedOf(index[e.apiId.toString()]) != e.updated || McOnline.toqueId(e.apiId) !in present
        }
        val todoIds = todo.map { it.apiId }.toHashSet()
        val rescored = ArrayList<Recipe>()
        for (e in entries) {
            val key = e.apiId.toString()
            if (e.apiId in todoIds || index[key] == e.stamp) continue
            val r = repo.get(McOnline.toqueId(e.apiId)) ?: continue
            rescored += McOnline.withScore(r, e)
            index[key] = e.stamp
            if (rescored.size >= 100) save(rescored, index, language)
        }
        save(rescored, index, language)
        val batch = ArrayList<Recipe>()
        var failures = 0
        var inARow = 0
        for ((i, e) in todo.withIndex()) {
            progress(tr("Recettes : %d / %d", i + 1, todo.size))
            try {
                val r = McOnline.parseRecipe(get(McOnline.recipeUrl(e.apiId), language))
                if (r != null) batch += r
                index[e.apiId.toString()] = e.stamp
                inARow = 0
            } catch (ex: IOException) {
                failures++
                inARow++
                log.write("synchro", "recette ${e.apiId} : ${ex.message}")
                if (inARow >= 5) {
                    save(batch, index, language)
                    return LidlSync.Report(false, tr("Serveur de recettes injoignable : %s", ex.message ?: ex.toString()))
                }
            }
            if (batch.size >= 25) save(batch, index, language)
            pause(250)
        }
        save(batch, index, language)

        // 3. Le catalogue suit la liste du site (recettes retirées supprimées).
        val keep = entries.map { McOnline.toqueId(it.apiId) }.toHashSet()
        repo.removeAllExcept(keep, rebuild = false, origin = Origin.LIDL)
        val kept = entries.map { it.apiId.toString() }.toHashSet()
        index.keys.retainAll(kept)
        writeIndex(language, index)
        repo.refresh()

        // 4. Photos manquantes.
        fetcher.photos(keep, 150, { log.write("synchro", it) }, progress)

        val n = repo.index.entries.count { Origin.LIDL.accepts(it.id) }
        val note = if (failures > 0) " " + tr("(%d en échec, réessayées à la prochaine synchro)", failures) else ""
        return LidlSync.Report(true, tr("%d recettes Lidl à jour", n) + note, n)
    }

    private fun save(batch: MutableList<Recipe>, index: Map<String, String>, language: String) {
        if (batch.isNotEmpty()) repo.upsertAll(ArrayList(batch), rebuild = false)
        batch.clear()
        writeIndex(language, index)
    }

    private fun get(url: String, language: String): String =
        String(fetcher.download(url, McOnline.headers(language), 4 * 1024 * 1024), Charsets.UTF_8)

    private fun readIndex(): Pair<String, Map<String, String>> = try {
        val m = Json.parse(indexFile.readText()).obj()
        val ids = m["recipes"].obj().mapValues { (_, v) -> v?.toString().orEmpty() }
        (m.str("language").orEmpty()) to ids
    } catch (_: Exception) {
        "" to emptyMap()
    }

    private fun writeIndex(language: String, index: Map<String, String>) {
        try {
            val json = Json.write(linkedMapOf("language" to language, "recipes" to index))
            indexFile.replaceAtomically { it.write(json.toByteArray()) }
        } catch (e: Exception) {
            log.write("synchro", "index non enregistré : $e")
        }
    }

    private fun finish(r: LidlSync.Report): LidlSync.Report {
        // Terminée (réussie ou arrêtée sur une erreur) : pas de reprise automatique au démarrage.
        prefs.lidlResumes = 0
        prefs.lidlSyncPending = false
        val stamp = SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(Date())
        prefs.lastSyncMs = System.currentTimeMillis()
        prefs.lastSyncResult = "$stamp : ${r.message}"
        log.write("synchro", r.message)
        return r
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private val running = AtomicBoolean(false)

        val isRunning: Boolean get() = running.get()

        /** Dernier message de progression de la synchro en cours (vide sinon). */
        @Volatile var current: String = ""
            private set
    }
}
