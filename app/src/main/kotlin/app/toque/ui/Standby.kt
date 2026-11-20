package app.toque.ui

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.machine.Snapshot
import app.toque.recipes.CookomixSync
import app.toque.recipes.OnlineSync

/**
 * Mise en veille de l'écran : après [minutes] sans contact (écran ou bouton rotatif), fond noir,
 * heure et date peu lumineuses et rétroéclairage au minimum. L'écran reste allumé au sens d'Android
 * (pas d'écran de verrouillage, bouton rotatif toujours actif) ; le premier geste ne fait que
 * réveiller. Jamais pendant un programme, un Turbo, une alerte de la base, ni pendant une
 * synchro de recettes (la synchro continuerait, mais sa progression doit rester visible).
 * L'inactivité se mesure sans l'horloge du robot, qui saute quand elle est remise à l'heure.
 *
 * En option, la veille fait défiler les photos d'un album ([PhotoFrame]) à la place de l'heure, à
 * une luminosité choisie. Un premier geste remet alors l'écran en pleine lumière pour regarder la
 * photo, un second réveille ; tourner le bouton rotatif fait défiler les photos. Sans réseau ou si
 * l'album est illisible, la veille affiche l'heure.
 */
class Standby(private val activity: Activity, root: FrameLayout, private val prefs: Prefs, log: Logbook, private val snapshot: () -> Snapshot) {
    private val main = Handler(Looper.getMainLooper())
    private var lastActivity = SystemClock.elapsedRealtime()
    private var running = false

    var sleeping = false
        private set

    private val photos = PhotoFrame(activity, prefs, log)
    /** Le diaporama est à l'écran (sinon : l'heure). */
    var showingPhotos = false
        private set
    /** Diaporama en pleine lumière après un premier geste ; le suivant réveille. */
    private var bright = false
    private val dim = Runnable { setBright(false) }
    private val retryPhotos = Runnable { if (sleeping && !showingPhotos) showPhotos() }

    private val clock = TextView(activity).apply {
        setTextColor(0xFF4A4F57.toInt())
        textSize = 96f
        typeface = Ui.THIN
        gravity = Gravity.CENTER
    }

    private val date = TextView(activity).apply {
        setTextColor(0xFF3A3F47.toInt())
        textSize = 26f
        typeface = Ui.LIGHT
        gravity = Gravity.CENTER
    }

    private val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        // Absorbe les touchers (le réveil est géré par l'activité, avant la distribution).
        isClickable = true
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(clock)
            addView(date)
        }
        addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        photos.view.visibility = View.GONE
        addView(photos.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    init {
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        photos.onUnavailable = {
            showClock()
            main.postDelayed(retryPhotos, PHOTOS_RETRY_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (sleeping) showTime()
            else if (shouldSleep()) sleep(prefs.standbyPhotos && prefs.photoAlbum.isNotEmpty())
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun canSleep(s: Snapshot) = !s.busy && !s.jobActive && !s.turboActive && !s.hasFault &&
        !OnlineSync.isRunning && !CookomixSync.isRunning

    private fun shouldSleep(): Boolean {
        val m = prefs.screenSleepMinutes
        return m > 0 && SystemClock.elapsedRealtime() - lastActivity >= m * 60_000L && canSleep(snapshot())
    }

    fun start() {
        if (running) return
        running = true
        lastActivity = SystemClock.elapsedRealtime()
        main.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    /** Un geste de l'utilisateur : remet le compteur à zéro. */
    fun touched() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    /** L'état de la base a changé : programme lancé ou alerte → on réveille pour qu'elle se voie. */
    fun onSnapshot(s: Snapshot) {
        if (sleeping && !canSleep(s)) wake()
    }

    private fun sleep(withPhotos: Boolean) {
        sleeping = true
        view.visibility = View.VISIBLE
        view.bringToFront()
        if (withPhotos) showPhotos() else showClock()
    }

    /** Lance le diaporama tout de suite (bouton des Réglages) ; faux si la veille n'est pas permise. */
    fun startPhotos(): Boolean {
        if (sleeping || !canSleep(snapshot())) return false
        sleep(withPhotos = true)
        return true
    }

    private fun showPhotos() {
        showingPhotos = true
        photos.view.visibility = View.VISIBLE
        setBright(false)
        photos.start()
    }

    private fun showClock() {
        main.removeCallbacks(dim)
        photos.stop()
        photos.view.visibility = View.GONE
        showingPhotos = false
        bright = false
        showTime()
        setBrightness(CLOCK_BRIGHTNESS)
    }

    /** Diaporama en pleine lumière le temps de regarder la photo, puis retour à sa luminosité. */
    private fun setBright(on: Boolean) {
        main.removeCallbacks(dim)
        bright = on
        photos.showHint(on)
        setBrightness(if (on) 1f else prefs.photoBrightness.coerceIn(1, 100) / 100f)
        if (on) main.postDelayed(dim, BRIGHT_MS)
    }

    /**
     * Un geste (toucher, bouton rotatif) pendant la veille : il réveille, sauf le premier pendant
     * le diaporama, qui ne fait que monter la lumière.
     */
    fun interact() {
        if (sleeping && showingPhotos && !bright) setBright(true) else wake()
    }

    /** Bouton rotatif pendant le diaporama : photo suivante ([delta] > 0) ou précédente. */
    fun turnPhotos(delta: Int) {
        if (!showingPhotos) return
        if (delta > 0) photos.next() else photos.previous()
    }

    fun wake() {
        lastActivity = SystemClock.elapsedRealtime()
        if (!sleeping) return
        sleeping = false
        main.removeCallbacks(dim)
        main.removeCallbacks(retryPhotos)
        photos.stop()
        photos.view.visibility = View.GONE
        showingPhotos = false
        bright = false
        view.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun showTime() {
        clock.text = MainActivity.now()
        date.text = MainActivity.today()
        if (showingPhotos) photos.refreshClock()
    }

    private fun setBrightness(value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
    }

    private companion object {
        const val TICK_MS = 15_000L
        const val CLOCK_BRIGHTNESS = 0.02f
        /** Durée de la pleine lumière après un premier geste sur le diaporama. */
        const val BRIGHT_MS = 10_000L
        /** Album illisible ou réseau absent : nouvel essai du diaporama. */
        const val PHOTOS_RETRY_MS = 2 * 60_000L
    }
}
