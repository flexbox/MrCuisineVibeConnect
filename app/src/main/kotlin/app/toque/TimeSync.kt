package app.toque

import android.content.Context
import android.os.SystemClock
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.time.ZoneLookup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date et heure du robot, réglées toutes seules :
 *  - l'heure vient d'un serveur (en-tête Date d'une réponse HTTP) et corrige l'horloge du système
 *    (root) si elle s'en écarte de plus de deux minutes ;
 *  - le fuseau horaire est trouvé d'après la connexion Internet (géolocalisation de l'adresse IP),
 *    ou choisi à la main dans les réglages, puis appliqué au système (root).
 * Le robot n'a pas de pile d'horloge : à chaque démarrage il repart d'une date ancienne. Tant
 * qu'aucun serveur n'a répondu, l'horloge est remise à la dernière heure connue (notée toutes les
 * cinq minutes) : la date est alors assez proche pour que les connexions sécurisées (HTTPS)
 * fonctionnent, et l'interface affiche « --:-- » plutôt qu'une heure fausse.
 */
class TimeSync(private val context: Context, private val prefs: Prefs, private val log: Logbook) {
    class Result(val ok: Boolean, val zone: String, val message: String)

    /** Heure d'un serveur, et l'instant (temps écoulé depuis le démarrage) où elle a été reçue. */
    private class ServerTime(private val ms: Long, private val receivedAt: Long = SystemClock.elapsedRealtime()) {
        fun now(): Long = ms + SystemClock.elapsedRealtime() - receivedAt
    }

    fun run(): Result = synchronized(LOCK) { runLocked() }

    private fun runLocked(): Result {
        val wanted = prefs.timeZone
        if (wanted != ZoneLookup.AUTO && wanted !in available) return Result(false, current(), tr("Fuseau horaire inconnu : %s", wanted))
        val root = RootShell.available()

        // L'heure d'abord : avec une horloge fausse, les services de fuseau en HTTPS ne répondent pas.
        var time = serverTime()
        var fixed = false
        if (root) {
            // Heure réseau d'Android (NTP), au cas où elle aurait été désactivée.
            RootShell.run("settings put global auto_time 1")
            if (time != null) fixed = fixClock(time) else restoreLastKnown()
        }

        var zone: String? = null
        var source = tr("choix manuel")
        if (wanted != ZoneLookup.AUTO) zone = wanted
        else for (p in ZoneLookup.PROVIDERS) {
            try {
                val r = Http.get(p.url, mapOf("Accept" to "application/json"), timeoutMs = 10_000, maxBytes = 64 * 1024)
                if (time == null && r.serverMs >= MIN_PLAUSIBLE_MS) {
                    time = ServerTime(r.serverMs)
                    if (root) fixed = fixClock(time)
                }
                val z = ZoneLookup.zoneOf(r.body)
                if (z != null && z in available) {
                    zone = z
                    source = p.name
                    break
                }
                log.write("heure", "${p.name} : pas de fuseau dans la réponse")
            } catch (e: Exception) {
                log.write("heure", "${p.name} : ${e.message ?: e.toString()}")
            }
        }

        if (time != null && agrees(time)) confirm(context)
        if (!root) return Result(false, current(), tr("Accès root nécessaire pour régler l'heure du robot."))
        if (zone == null) return Result(false, current(), tr("Fuseau horaire introuvable : vérifiez le Wi-Fi."))

        if (zone != current()) {
            val r = RootShell.run("service call alarm 3 s16 $zone")
            if (!r.ok || prop() != zone) {
                // Repli : propriété système puis annonce du changement aux applis.
                RootShell.run("setprop persist.sys.timezone $zone && am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone $zone")
            }
            TimeZone.setDefault(null)
            log.write("heure", "fuseau $zone ($source)")
        }
        if (prop() != zone && current() != zone) return Result(false, current(), tr("Le système a refusé le fuseau %s", zone))
        prefs.lastZone = zone
        if (!trusted) return Result(false, zone, tr("Heure non confirmée par le réseau : vérifiez le Wi-Fi."))
        return Result(true, zone, if (fixed) tr("Heure corrigée : %s (%s)", clock(), zone) else tr("Il est %s (%s)", clock(), zone))
    }

    /** Heure d'un serveur joignable sans connexion sécurisée, donc même avec une horloge fausse. */
    private fun serverTime(): ServerTime? = try {
        Http.serverDate(TIME_URL, timeoutMs = 5_000).takeIf { it >= MIN_PLAUSIBLE_MS }?.let { ServerTime(it) }
    } catch (e: Exception) {
        log.write("heure", "heure du réseau : ${e.message ?: e.toString()}")
        null
    }

    private fun agrees(time: ServerTime) = Math.abs(System.currentTimeMillis() - time.now()) < TOLERANCE_MS

    /** Corrige l'horloge si elle s'écarte de l'heure du serveur ; vrai si elle a été corrigée. */
    private fun fixClock(time: ServerTime): Boolean {
        if (agrees(time)) return false
        val ok = setClock(time.now())
        log.write("heure", if (ok) "horloge corrigée d'après le réseau" else "correction de l'horloge refusée")
        return ok
    }

    /** Sans réponse du réseau : remet l'horloge à la dernière heure connue si elle est en retard dessus. */
    private fun restoreLastKnown() {
        val saved = lastKnown(context)
        if (saved < MIN_PLAUSIBLE_MS || System.currentTimeMillis() >= saved) return
        if (!setClock(saved)) return
        log.write("heure", "horloge remise à la dernière heure connue, en attendant le réseau")
        // La note ne vaut plus confirmation : elle date d'avant le redémarrage du robot.
        try {
            file(context).replaceAtomically { it.write("$saved".toByteArray()) }
        } catch (_: Exception) {
        }
    }

    private fun prop(): String = RootShell.run("getprop persist.sys.timezone").out.trim()

    companion object {
        /** 1er janvier 2025 : en dessous, une heure (serveur ou horloge du robot) n'est pas crédible. */
        private const val MIN_PLAUSIBLE_MS = 1_735_689_600_000L
        private const val TOLERANCE_MS = 120_000L
        private const val REMEMBER_EVERY_MS = 5 * 60_000L
        /** Page vide servie en HTTP simple, celle qu'Android interroge pour tester la connexion. */
        private const val TIME_URL = "http://connectivitycheck.gstatic.com/generate_204"
        private val LOCK = Any()

        /** Horloge confirmée par un serveur depuis le démarrage du robot. */
        @Volatile private var confirmed = false

        /** L'heure du robot peut-elle être affichée ? (sinon : pas confirmée depuis le démarrage) */
        val trusted: Boolean get() = confirmed && System.currentTimeMillis() >= MIN_PLAUSIBLE_MS

        /** Règle l'horloge du système (root) ; vrai si elle est ensuite à moins d'une minute de [ms]. */
        private fun setClock(ms: Long): Boolean {
            RootShell.run("service call alarm 2 i64 $ms")
            if (Math.abs(System.currentTimeMillis() - ms) > 60_000L) {
                val utc = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                RootShell.run("date -u ${utc.format(Date(ms))}")
            }
            return Math.abs(System.currentTimeMillis() - ms) < 60_000L
        }

        private fun confirm(context: Context) {
            confirmed = true
            remember(context)
        }

        /** Heure du robot et temps écoulé depuis son démarrage, notés ensemble par [remember]. */
        private fun file(context: Context) = File(context.filesDir, "derniere-heure.txt")

        private fun saved(context: Context): List<Long> = try {
            file(context).readText().trim().split(' ').map { it.toLong() }
        } catch (_: Exception) {
            emptyList()
        }

        /** Dernière heure connue (0 si aucune). */
        private fun lastKnown(context: Context): Long = saved(context).firstOrNull() ?: 0L

        /**
         * L'appli redémarre sans que le robot ait redémarré (mise à jour, plantage) : l'horloge
         * confirmée avant reste bonne. On le reconnaît à ce que l'heure et le temps écoulé depuis
         * le démarrage ont avancé d'autant depuis la dernière note.
         */
        private fun confirmedBeforeRestart(context: Context): Boolean {
            val (wall, elapsed) = saved(context).takeIf { it.size == 2 } ?: return false
            val sinceElapsed = SystemClock.elapsedRealtime() - elapsed
            return sinceElapsed >= 0 && Math.abs(System.currentTimeMillis() - wall - sinceElapsed) < TOLERANCE_MS
        }

        /** Note l'heure courante si elle est fiable, pour le prochain démarrage. */
        private fun remember(context: Context) {
            if (!trusted) return
            try {
                file(context).replaceAtomically { it.write("${System.currentTimeMillis()} ${SystemClock.elapsedRealtime()}".toByteArray()) }
            } catch (_: Exception) {
            }
        }

        private val available: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }

        fun current(): String = TimeZone.getDefault().id

        /** Heure affichée ; « --:-- » tant qu'elle n'est pas confirmée depuis le démarrage. */
        fun clock(): String {
            if (!trusted) return "--:--"
            return SimpleDateFormat("HH:mm", Locale.FRANCE).apply { timeZone = TimeZone.getDefault() }.format(Date())
        }

        @Volatile private var started = false

        /**
         * Au lancement de l'interface : réglage dès que le réseau répond (le Wi-Fi met un moment à
         * se connecter au démarrage du robot), puis toutes les 12 heures. Entre-temps, l'heure est
         * notée toutes les cinq minutes pour le prochain démarrage.
         */
        fun start(context: Context, prefs: Prefs, log: Logbook) {
            if (started) return
            started = true
            val t = Thread {
                confirmed = confirmedBeforeRestart(context)
                val retries = longArrayOf(3_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
                var i = 0
                while (true) {
                    val r = try {
                        TimeSync(context, prefs, log).run()
                    } catch (e: Throwable) {
                        log.write("heure", "erreur : ${e.message}")
                        Result(false, current(), "")
                    }
                    var wait = if (r.ok) 12 * 3600_000L else retries[minOf(i++, retries.size - 1)]
                    if (r.ok) i = 0
                    while (wait > 0) {
                        val slice = minOf(wait, REMEMBER_EVERY_MS)
                        try {
                            Thread.sleep(slice)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        wait -= slice
                        remember(context)
                    }
                }
            }
            t.isDaemon = true
            t.name = "toque-heure"
            t.start()
        }

        /** Attend que l'heure soit confirmée (donc que le réseau réponde) ; faux si [maxMs] est dépassé. */
        fun awaitTrusted(maxMs: Long): Boolean {
            val end = SystemClock.elapsedRealtime() + maxMs
            while (!trusted) {
                if (SystemClock.elapsedRealtime() >= end) return false
                try {
                    Thread.sleep(2_000)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return true
        }
    }
}
