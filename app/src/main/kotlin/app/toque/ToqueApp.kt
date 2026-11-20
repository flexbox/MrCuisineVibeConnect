package app.toque

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.machine.MachineClient
import app.toque.recipes.RecipeRepo
import app.toque.ui.MainActivity
import java.util.Locale

class ToqueApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var log: Logbook
        private set
    val machine: MachineClient by lazy { MachineClient(this) }
    val recipes: RecipeRepo by lazy { RecipeRepo(this) }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = Logbook(this)
        LanguageFiles.install(this, log)
        Tls.init(this, log) // racines récentes (GitHub, Let's Encrypt) absentes d'Android 6, lues à la première connexion
        I18n.select(I18n.resolve(prefs.language, Locale.getDefault().language).code)
        if (!isMainProcess()) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log.crash("interface", error)
                val now = System.currentTimeMillis()
                val recent = now - prefs.lastCrashMs < 10_000
                prefs.lastCrashMs = now
                if (recent) throw error // évite une boucle de relances
                // Relance discrète de l'interface ; le processus machine, lui, continue.
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_ONE_SHOT)
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
        log.write("interface", "démarrage")
        TimeSync.start(this, prefs, log)
        resumeInterruptedSyncs()
    }

    /**
     * Synchro interrompue par un arrêt de l'appli (mise à jour, coupure de courant, plantage) :
     * elle reprend toute seule, là où elle s'était arrêtée (index enregistré au fil de l'eau).
     * Les deux synchros reprennent en même temps, comme elles avaient été lancées.
     */
    private fun resumeInterruptedSyncs() {
        if (prefs.lidlSyncPending) resume("synchro", "toque-reprise-lidl", { prefs.lidlResumes }, { prefs.lidlResumes = it },
            giveUp = { prefs.lidlSyncPending = false; prefs.lastSyncResult = it }) {
            app.toque.recipes.OnlineSync(this, prefs, recipes, log).sync()
        }
        if (prefs.cookomixSyncPending) resume("cookomix", "toque-reprise-cookomix", { prefs.cookomixResumes }, { prefs.cookomixResumes = it },
            giveUp = { prefs.cookomixSyncPending = false; prefs.cookomixLastResult = it }) {
            app.toque.recipes.CookomixSync(this, prefs, recipes, log).sync()
        }
    }

    /** [resumes] compte les reprises de suite : une synchro qui fait tomber l'appli ne reprend pas plus de 3 fois. */
    private fun resume(tag: String, name: String, resumes: () -> Int, setResumes: (Int) -> Unit, giveUp: (String) -> Unit, sync: () -> Unit) {
        if (resumes() >= MAX_RESUMES) {
            setResumes(0)
            val message = tr("Synchronisation interrompue %d fois de suite : relancez-la à la main.", MAX_RESUMES)
            giveUp(message)
            log.write(tag, message)
            return
        }
        resumesScheduled.incrementAndGet()
        Thread({
            try {
                Thread.sleep(RESUME_DELAY_MS)
                // Au démarrage du robot, le Wi-Fi puis l'heure mettent un moment à arriver : lancée
                // avant, la synchro échouerait aussitôt et ne reprendrait plus.
                if (!TimeSync.awaitTrusted(RESUME_WAIT_MS)) {
                    log.write(tag, "pas de réseau : reprise remise au prochain démarrage")
                    return@Thread
                }
                setResumes(resumes() + 1)
                log.write(tag, "reprise de la synchro interrompue")
                sync()
            } catch (e: Exception) {
                log.crash(tag, e)
            } finally {
                resumesScheduled.decrementAndGet()
            }
        }, name).start()
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: return true
        return name == packageName
    }

    companion object {
        fun of(c: Context): ToqueApp = c.applicationContext as ToqueApp

        /** Délai avant la reprise d'une synchro interrompue (laisse l'appli démarrer). */
        private const val RESUME_DELAY_MS = 8_000L
        /** Attente du réseau avant de renoncer à la reprise pour cette fois. */
        private const val RESUME_WAIT_MS = 10 * 60_000L
        private const val MAX_RESUMES = 3

        /** Reprises en attente du réseau ou en cours (pour l'affichage dans Réglages). */
        val resumesScheduled = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
