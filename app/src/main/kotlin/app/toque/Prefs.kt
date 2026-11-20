package app.toque

import android.content.Context
import android.content.SharedPreferences
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey

/**
 * Réglages persistants, lisibles par les deux processus (interface et machine).
 * Écriture synchrone (commit) : le processus machine relit le fichier juste après.
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    private val p: SharedPreferences = context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)

    enum class Mode(val key: String, private val fr: String) {
        DEMO("demo", trKey("Démo (base simulée)")),
        TWIN("twin", trKey("Jumeau numérique (PC)")),
        HARDWARE("hardware", trKey("Machine réelle"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DEMO
        }
    }

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) {
            p.edit().putString("mode", v.key).commit()
        }

    /** Langue de l'interface : « auto » (celle du système), « fr » ou « en ». */
    var language: String
        get() = p.getString("language", I18n.AUTO) ?: I18n.AUTO
        set(v) {
            p.edit().putString("language", v).commit()
        }

    var twinHost: String
        get() = p.getString("twinHost", "127.0.0.1") ?: "127.0.0.1"
        set(v) {
            p.edit().putString("twinHost", v).commit()
        }

    var twinPort: Int
        get() = p.getInt("twinPort", 8765)
        set(v) {
            p.edit().putInt("twinPort", v).commit()
        }

    var serialDevice: String
        get() = p.getString("serialDevice", "/dev/ttyMT0") ?: "/dev/ttyMT0"
        set(v) {
            p.edit().putString("serialDevice", v).commit()
        }

    var vendorPackage: String
        get() = p.getString("vendorPackage", "de.silpion.mc2") ?: "de.silpion.mc2"
        set(v) {
            p.edit().putString("vendorPackage", v).commit()
        }

    var requireBowl: Boolean
        get() = p.getBoolean("requireBowl", false)
        set(v) {
            p.edit().putBoolean("requireBowl", v).commit()
        }

    /** Montée progressive de la vitesse quand le contenu est chaud (≥ 60 °C). */
    var hotRamp: Boolean
        get() = p.getBoolean("hotRamp", true)
        set(v) {
            p.edit().putBoolean("hotRamp", v).commit()
        }

    /** Recettes : enchaîner sans appui les étapes machine qui se suivent sans geste à faire. */
    var chainSteps: Boolean
        get() = p.getBoolean("chainSteps", true)
        set(v) {
            p.edit().putBoolean("chainSteps", v).commit()
        }

    /** Onglet de provenance des recettes (Tout / Lidl / Cookomix), mémorisé. */
    var recipeOrigin: String
        get() = p.getString("recipeOrigin", "ALL") ?: "ALL"
        set(v) {
            p.edit().putString("recipeOrigin", v).commit()
        }

    /** Cuisson manuelle : réglages par molettes tactiles (true) ou par boutons − / + (false). */
    var manualDials: Boolean
        get() = p.getBoolean("manualDials", false)
        set(v) {
            p.edit().putBoolean("manualDials", v).commit()
        }

    /** Mise en veille de l'écran après ce nombre de minutes sans contact (0 : jamais). */
    var screenSleepMinutes: Int
        get() = p.getInt("screenSleepMinutes", 10)
        set(v) {
            p.edit().putInt("screenSleepMinutes", v).commit()
        }

    /**
     * Faire confirmer (« J'ai vérifié ce réglage ») chaque réglage machine importé d'une recette
     * avant de pouvoir le lancer. Les règles de sécurité s'appliquent même désactivé.
     */
    var confirmImported: Boolean
        get() = p.getBoolean("confirmImported", true)
        set(v) {
            p.edit().putBoolean("confirmImported", v).commit()
        }

    /** Pendant la veille : diaporama de l'album [photoAlbum] à la place de l'heure. */
    var standbyPhotos: Boolean
        get() = p.getBoolean("standbyPhotos", false)
        set(v) {
            p.edit().putBoolean("standbyPhotos", v).apply()
        }

    /** Code du lien de partage de l'album Google Photos (photos.app.goo.gl/<code>), vide si aucun. */
    var photoAlbum: String
        get() = p.getString("photoAlbum", "") ?: ""
        set(v) {
            p.edit().putString("photoAlbum", v).apply()
        }

    /** Durée d'affichage d'une photo, en secondes. */
    var photoSeconds: Int
        get() = p.getInt("photoSeconds", 20)
        set(v) {
            p.edit().putInt("photoSeconds", v).apply()
        }

    /** Luminosité de l'écran pendant le diaporama, en pour cent. */
    var photoBrightness: Int
        get() = p.getInt("photoBrightness", 40)
        set(v) {
            p.edit().putInt("photoBrightness", v).apply()
        }

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) {
            p.edit().putBoolean("sound", v).commit()
        }

    /** Fuseau horaire : « auto » (d'après la connexion Internet) ou un identifiant IANA choisi à la main. */
    var timeZone: String
        get() = p.getString("timeZone", "auto") ?: "auto"
        set(v) {
            p.edit().putString("timeZone", v).commit()
        }

    /** Dernier fuseau appliqué avec succès (affiché dans les réglages). */
    var lastZone: String
        get() = p.getString("lastZone", "") ?: ""
        set(v) {
            p.edit().putString("lastZone", v).commit()
        }

    var autoSync: Boolean
        get() = p.getBoolean("autoSync", true)
        set(v) {
            p.edit().putBoolean("autoSync", v).commit()
        }

    /** Synchro Lidl commencée et pas terminée (appli arrêtée en cours) : reprise au démarrage. */
    var lidlSyncPending: Boolean
        get() = p.getBoolean("lidlSyncPending", false)
        set(v) {
            p.edit().putBoolean("lidlSyncPending", v).commit()
        }

    /** Synchro Cookomix commencée et pas terminée : reprise au démarrage. */
    var cookomixSyncPending: Boolean
        get() = p.getBoolean("cookomixSyncPending", false)
        set(v) {
            p.edit().putBoolean("cookomixSyncPending", v).commit()
        }

    /** Reprises automatiques consécutives de la synchro Lidl qui ne se termine pas (évite une boucle). */
    var lidlResumes: Int
        get() = p.getInt("lidlResumes", 0)
        set(v) {
            p.edit().putInt("lidlResumes", v).commit()
        }

    /** Reprises automatiques consécutives de la synchro Cookomix qui ne se termine pas. */
    var cookomixResumes: Int
        get() = p.getInt("cookomixResumes", 0)
        set(v) {
            p.edit().putInt("cookomixResumes", v).commit()
        }

    var lastSyncMs: Long
        get() = p.getLong("lastSyncMs", 0)
        set(v) {
            p.edit().putLong("lastSyncMs", v).commit()
        }

    var lastSyncResult: String
        get() = p.getString("lastSyncResult", "") ?: ""
        set(v) {
            p.edit().putString("lastSyncResult", v).commit()
        }

    var vendorSession: Boolean
        get() = p.getBoolean("vendorSession", false)
        set(v) {
            p.edit().putBoolean("vendorSession", v).commit()
        }

    /** Bouton « Retour à Toque » à afficher : Toque a ouvert une autre appli (robot sans bouton Accueil). */
    var returnBubble: Boolean
        get() = p.getBoolean("returnBubble", false)
        set(v) {
            p.edit().putBoolean("returnBubble", v).commit()
        }

    /** Ouvrir Toque au démarrage du robot (désactivé par « Revenir à l'appli d'origine »). */
    var bootLaunch: Boolean
        get() = p.getBoolean("bootLaunch", true)
        set(v) {
            p.edit().putBoolean("bootLaunch", v).commit()
        }

    var knobLeft: Int
        get() = p.getInt("knobLeft", -1)
        set(v) {
            p.edit().putInt("knobLeft", v).commit()
        }

    var knobRight: Int
        get() = p.getInt("knobRight", -1)
        set(v) {
            p.edit().putInt("knobRight", v).commit()
        }

    var knobPress: Int
        get() = p.getInt("knobPress", -1)
        set(v) {
            p.edit().putInt("knobPress", v).commit()
        }

    var lastCrashMs: Long
        get() = p.getLong("lastCrashMs", 0)
        set(v) {
            p.edit().putLong("lastCrashMs", v).commit()
        }

    var welcomeDone: Boolean
        get() = p.getBoolean("welcomeDone", false)
        set(v) {
            p.edit().putBoolean("welcomeDone", v).commit()
        }

    var builtinVersion: Int
        get() = p.getInt("builtinVersion", 0)
        set(v) {
            p.edit().putInt("builtinVersion", v).commit()
        }

    /** Recette guidée en cours (id + étape), pour reprendre après un redémarrage. */
    var guidedRecipe: String
        get() = p.getString("guidedRecipe", "") ?: ""
        set(v) {
            p.edit().putString("guidedRecipe", v).commit()
        }

    var guidedStep: Int
        get() = p.getInt("guidedStep", 0)
        set(v) {
            p.edit().putInt("guidedStep", v).commit()
        }

    // ---- Cookomix

    /** Synchro nocturne des recettes Cookomix (désactivée tant que l'utilisateur ne l'a pas lancée une fois). */
    var cookomixAutoSync: Boolean
        get() = p.getBoolean("cookomixAutoSync", false)
        set(v) {
            p.edit().putBoolean("cookomixAutoSync", v).commit()
        }

    /** Importer aussi les recettes dont un réglage a dû être adapté au MCC. */
    var cookomixAdapted: Boolean
        get() = p.getBoolean("cookomixAdapted", true)
        set(v) {
            p.edit().putBoolean("cookomixAdapted", v).commit()
        }

    var cookomixLastResult: String
        get() = p.getString("cookomixLastResult", "") ?: ""
        set(v) {
            p.edit().putString("cookomixLastResult", v).commit()
        }

    var cookomixLastSyncMs: Long
        get() = p.getLong("cookomixLastSyncMs", 0)
        set(v) {
            p.edit().putLong("cookomixLastSyncMs", v).commit()
        }

}
