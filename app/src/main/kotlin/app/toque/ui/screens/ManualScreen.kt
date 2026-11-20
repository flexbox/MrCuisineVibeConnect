package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.Limits
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.TempRing
import app.toque.ui.Ui
import app.toque.ui.ValueCard

/**
 * Cuisson manuelle. Les réglages incompatibles sont corrigés à la saisie (vitesse limitée en
 * chauffe, en sens inverse ou avec le fouet) plutôt que refusés au démarrage.
 */
class ManualScreen(a: MainActivity, preset: app.toque.core.recipes.StepMachine? = null) : Screen(a) {
    override val title = JobKind.MANUAL.label

    private var seconds = preset?.seconds?.takeIf { it > 0 } ?: 60
    private var tempLevel = preset?.temperatureLevel ?: 0
    private var speed = preset?.speed?.coerceIn(1, 10) ?: 2
    private var reverse = preset?.reverse ?: false
    private var accessory = preset?.accessory ?: Accessory.BLADE
    private var selected = 0

    private lateinit var timeCard: ValueCard
    private lateinit var tempCard: ValueCard
    private lateinit var speedCard: ValueCard
    private lateinit var dirRow: LinearLayout
    private lateinit var accRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private var dials = a.prefs.manualDials
    private var lastToastMs = 0L
    private lateinit var ring: TempRing
    private lateinit var weight: TextView
    private lateinit var startBtn: LinearLayout
    private lateinit var hint: TextView
    private lateinit var turbo: HoldButton
    private var live = false
    private var wasLive = false
    private var wasManualJob = false
    private var pendingEdit = false
    private var timeEdited = false
    private var configuredSeconds = seconds
    private val pushLive = Runnable { sendAdjust() }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(20))
        val left = Ui.vertical(c)
        val cards = Ui.horizontal(c)
        timeCard = ValueCard(c, tr("Durée"), Icon.Kind.CLOCK)
        tempCard = ValueCard(c, tr("Température"), Icon.Kind.THERMO)
        speedCard = ValueCard(c, tr("Vitesse"), Icon.Kind.SPEED)
        val all = listOf(timeCard, tempCard, speedCard)
        for ((i, card) in all.withIndex()) {
            card.onSelect = { selected = i; refresh() }
            card.onMinus = { change(i, -1) }
            card.onPlus = { change(i, 1) }
            card.dial.onPick = { pos -> pick(i, pos) }
            cards.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < 2) rightMargin = Ui.u(14) })
        }
        left.addView(cards, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val options = Ui.horizontal(c)
        dirRow = Ui.horizontal(c)
        accRow = Ui.horizontal(c)
        options.addView(dirRow)
        options.addView(Ui.spacer(c, w = 14))
        options.addView(accRow)
        options.addView(Ui.spacer(c, w = 14))
        modeRow = Ui.horizontal(c)
        options.addView(modeRow)
        left.addView(options, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(20) })

        val right = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        right.background = Ui.round(Ui.SURFACE, 26f)
        right.setPadding(Ui.u(18), Ui.u(14), Ui.u(18), Ui.u(18))
        // Turbo en haut du panneau : à la suite des choix du bas il sortait de l'écran, et près de
        // Démarrer on l'aurait touché par mégarde.
        turbo = HoldButton(c, "Turbo", onDown = {
            a.machine.turboDown(accessory) { v -> if (!v.ok) a.refusal(v) }
        }, onUp = { a.machine.turboUp() })
        right.addView(turbo, Ui.lp(Ui.MATCH, Ui.u(60), b = 6))
        ring = TempRing(c)
        right.addView(ring, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        weight = Ui.text(c, "", 18f, Ui.TEXT2)
        right.addView(weight, Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
        hint = Ui.text(c, "", 16f, Ui.WARN).apply { gravity = Gravity.CENTER }
        right.addView(hint, Ui.lp(Ui.MATCH, Ui.WRAP, t = 8))
        startBtn = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 80, textSize = 24f) { start() }
        right.addView(startBtn, Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))
        root.addView(right, LinearLayout.LayoutParams(Ui.u(300), Ui.MATCH))
        refresh()
        return root
    }

    private val tempSteps: IntArray get() = intArrayOf(0) + (1..19).toList().toIntArray()

    private fun nextSeconds(s: Int, dir: Int): Int {
        val step = when {
            dir > 0 && s < 60 || dir < 0 && s <= 60 -> 5
            dir > 0 && s < 600 || dir < 0 && s <= 600 -> 30
            else -> 60
        }
        val max = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        return (s + dir * step).coerceIn(5, max)
    }

    /** Durées possibles pour la molette (pas de 5 s, puis 30 s, puis 1 min), selon la durée max. */
    private val durationsLong by lazy { durationList((Limits.MAX_MS_DEFAULT / 1000).toInt()) }
    private val durationsShort by lazy { durationList((Limits.MAX_MS_HIGH_SPEED / 1000).toInt()) }

    private fun durations(): List<Int> = if (speed > 6) durationsShort else durationsLong

    private fun durationList(max: Int): List<Int> {
        val out = ArrayList<Int>()
        var s = 5
        while (s <= max) {
            out += s
            s += when {
                s < 60 -> 5
                s < 600 -> 30
                else -> 60
            }
        }
        return out
    }

    private fun timeDialPosition(): Int {
        val times = durations()
        return times.indexOfFirst { it >= seconds }.let { if (it < 0) times.size - 1 else it }
    }

    private fun limitToast(text: String) {
        // Pendant qu'on fait glisser une molette, un seul message par seconde et demie.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToastMs > 1_500) a.toast(text)
        lastToastMs = now
    }

    /** Vitesse maximale permise par les réglages en cours, et la raison. */
    private fun speedLimit(): Pair<Int, String> {
        var max = 10
        var why = trKey("Vitesse %d maximum")
        if (tempLevel > 0) { max = Limits.MAX_SPEED_HEATED; why = trKey("En chauffe, vitesse %d maximum") }
        if (reverse && max > Limits.MAX_SPEED_REVERSE) { max = Limits.MAX_SPEED_REVERSE; why = trKey("En sens inverse, vitesse %d maximum") }
        if (accessory.maxSpeed < max) { max = accessory.maxSpeed; why = trKey("Avec le fouet, vitesse %d maximum") }
        return max to why
    }

    /** Molette : position visée → valeur, avec les mêmes limites que les boutons. */
    private fun pick(card: Int, target: Int) {
        selected = card
        when (card) {
            0 -> { val times = durations(); seconds = times[target.coerceIn(0, times.size - 1)] }
            1 -> {
                tempLevel = tempSteps[target.coerceIn(0, tempSteps.size - 1)]
                if (tempLevel > 0 && speed > Limits.MAX_SPEED_HEATED) {
                    speed = Limits.MAX_SPEED_HEATED
                    limitToast(tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                }
            }
            2 -> {
                val (max, why) = speedLimit()
                val wanted = (target + 1).coerceIn(1, 10)
                if (wanted > max) limitToast(tr(why, max))
                speed = minOf(wanted, max)
            }
        }
        applied(card)
    }

    private fun setDials(on: Boolean) {
        dials = on
        a.prefs.manualDials = on
        refresh()
    }

    private fun change(card: Int, dir: Int) {
        when (card) {
            0 -> seconds = nextSeconds(seconds, dir)
            1 -> {
                val i = tempSteps.indexOf(tempLevel).coerceAtLeast(0)
                tempLevel = tempSteps[(i + dir).coerceIn(0, tempSteps.size - 1)]
                if (tempLevel > 0 && speed > Limits.MAX_SPEED_HEATED) {
                    speed = Limits.MAX_SPEED_HEATED
                    a.toast(tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                }
            }
            2 -> {
                val (max, why) = speedLimit()
                val next = (speed + dir).coerceIn(1, 10)
                if (next > max) a.toast(tr(why, max)) else speed = next
            }
        }
        applied(card)
    }

    /** Après un changement (boutons, molette ou bouton rotatif) : bornes, affichage, envoi en direct. */
    private fun applied(card: Int) {
        val s = a.snapshot
        val maxS = ((if (speed > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT) / 1000).toInt()
        if (seconds > maxS) seconds = maxS
        refresh()
        if (live && s.kind?.name == "MANUAL") {
            if (card == 0) timeEdited = true
            pendingEdit = true
            view.removeCallbacks(pushLive)
            view.postDelayed(pushLive, 350)
        }
    }

    private fun setReverse(r: Boolean) {
        if (r && speed > Limits.MAX_SPEED_REVERSE) {
            speed = Limits.MAX_SPEED_REVERSE
            a.toast(tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
        }
        reverse = r
        refresh()
        if (live) { pendingEdit = true; view.removeCallbacks(pushLive); view.postDelayed(pushLive, 200) }
    }

    private fun setAccessory(acc: Accessory) {
        if (live) return
        accessory = acc
        if (speed > acc.maxSpeed) {
            speed = acc.maxSpeed
            a.toast(tr("%s : vitesse %d maximum", acc.label, acc.maxSpeed))
        }
        refresh()
    }

    private fun spec(hot: Boolean = false) = Programs.manual(
        speed, tempLevel, if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE,
        seconds * 1000L, accessory, hot,
    )

    private fun start() {
        val s = a.snapshot
        if (s.state == JobState.PAUSED && s.kind?.name == "MANUAL") {
            a.machine.resume(s.hotBlendConfirmed) { a.refusal(it) }
            return
        }
        configuredSeconds = seconds
        a.startJob(spec())
    }

    private fun sendAdjust() {
        pendingEdit = false
        val s = a.snapshot
        if (!s.jobActive || s.kind?.name != "MANUAL") return
        // La durée n'est renvoyée que si elle a été modifiée : changer la vitesse ne relance pas le minuteur.
        val total: Long? = if (timeEdited) s.elapsedMs + seconds * 1000L else null
        timeEdited = false
        a.machine.adjust(speed, tempLevel, reverse, total, s.hotBlendConfirmed) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND)) {
                a.confirm(tr("Contenu chaud"), tr("Le bol est à %d °C. Passer à la vitesse %d ?", a.snapshot.actualTempC, speed), tr("Confirmer"), icon = Icon.Kind.THERMO) {
                    a.machine.adjust(speed, tempLevel, reverse, total, true) { a.refusal(it) }
                }
            } else a.refusal(v)
        }
    }

    private fun refresh() {
        val maxS = if (speed > 6) "max 15 min" else "max 99 min"
        timeCard.set(Ui.clock(seconds * 1000L), "", maxS)
        if (tempLevel == 0) tempCard.set("—", "", tr("Sans chauffe"), Ui.TEXT2)
        else {
            val t = Mcc.celsiusForLevel(tempLevel)
            tempCard.set("$t", "°C", if (t >= 100) tr("Ébullition") else if (t >= 60) tr("Cuisson") else tr("Tiédir"), Ui.tempColor(t))
        }
        speedCard.set("$speed", "", when (speed) {
            1 -> tr("Mijoter")
            2, 3 -> tr("Mélanger")
            in 4..6 -> tr("Hacher")
            else -> tr("Mixer")
        } + if (reverse) " · " + tr("inverse") else "")
        val t = if (tempLevel == 0) 0 else Mcc.celsiusForLevel(tempLevel)
        timeCard.setDial(durations().size, timeDialPosition(), Ui.ACCENT)
        tempCard.setDial(tempSteps.size, tempSteps.indexOf(tempLevel).coerceAtLeast(0), if (t == 0) Ui.TEXT3 else Ui.tempColor(t))
        speedCard.setDial(10, speed - 1, Ui.ACCENT)
        for (card in listOf(timeCard, tempCard, speedCard)) card.setDialMode(dials)
        modeRow.removeAllViews()
        modeRow.addView(Ui.toggleChip(c, tr("Boutons"), !dials) { setDials(false) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        modeRow.addView(Ui.toggleChip(c, tr("Molettes"), dials) { setDials(true) })
        timeCard.select(selected == 0)
        tempCard.select(selected == 1)
        speedCard.select(selected == 2)

        dirRow.removeAllViews()
        dirRow.addView(Ui.toggleChip(c, tr("Sens normal"), !reverse) { setReverse(false) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        dirRow.addView(Ui.toggleChip(c, tr("Inverse"), reverse) { setReverse(true) })
        accRow.removeAllViews()
        accRow.addView(Ui.toggleChip(c, Accessory.BLADE.label, accessory == Accessory.BLADE) { setAccessory(Accessory.BLADE) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        accRow.addView(Ui.toggleChip(c, Accessory.WHISK.label, accessory == Accessory.WHISK) { setAccessory(Accessory.WHISK) })

        val v = SafetyPolicy.checkJob(spec(), if (a.snapshot.hasSample) a.snapshot.actualTempC else null)
            .filter { it.code != SafetyPolicy.HOT_BLEND }
        hint.text = v.firstOrNull()?.message ?: if (a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else ""
        Ui.setButtonEnabled(startBtn, v.isEmpty())
    }

    override fun onSnapshot(s: Snapshot) {
        ring.set(if (s.hasSample) s.actualTempC else 0, if (s.jobActive && s.jobTempLevel > 0) Mcc.celsiusForLevel(s.jobTempLevel) else
            if (tempLevel > 0) Mcc.celsiusForLevel(tempLevel) else 0, s.speedLevel)
        weight.text = if (s.hasSample) tr("Balance : %d g", s.weightG) else tr("Base non connectée")
        val manualJob = s.kind?.name == "MANUAL" && (s.state == JobState.RUNNING || s.state == JobState.STARTING || s.state == JobState.PAUSED)
        live = manualJob && s.state != JobState.PAUSED
        // Pendant une cuisson, température et vitesse ne bougent que d'un cran par geste (appui ou
        // glissé) : pas de saut de la vitesse 2 à 10 pendant que la lame tourne.
        for (card in listOf(tempCard, speedCard)) {
            card.repeatEnabled = !live
            card.dial.maxStepsPerGesture = if (live) 1 else 0
        }
        if (manualJob && !wasManualJob) adopt(s)
        wasManualJob = manualJob
        if (live && !pendingEdit) {
            // Pendant la cuisson, la carte Durée affiche le temps restant.
            val remaining = ((s.remainingMs + 999) / 1000).toInt().coerceAtLeast(1)
            if (remaining != seconds) {
                seconds = remaining
                timeCard.set(Ui.clock(seconds * 1000L), "", tr("restant"))
                timeCard.setDial(durations().size, timeDialPosition(), Ui.ACCENT)
            }
        }
        if (wasLive && !live && !manualJob) {
            seconds = configuredSeconds
            refresh()
        }
        wasLive = live
        val busy = s.busy
        startBtn.visibility = if (busy && s.state != JobState.PAUSED) View.GONE else View.VISIBLE
        (startBtn.getChildAt(1) as TextView).text = if (s.state == JobState.PAUSED && manualJob) tr("Reprendre") else tr("Démarrer")
        turbo.isEnabled = !busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
        if (live) hint.text = tr("Réglages appliqués en direct")
        else if (!busy) refreshHintOnly()
    }

    /**
     * Une cuisson manuelle est déjà en cours à l'ouverture de l'écran : il reprend ses réglages,
     * pour qu'une retouche en direct n'envoie pas à la base ceux affichés par défaut.
     */
    private fun adopt(s: Snapshot) {
        if (s.jobSpeed !in 1..10) return
        speed = s.jobSpeed
        tempLevel = s.jobTempLevel
        reverse = s.jobReverse
        accessory = try { Accessory.valueOf(s.accessory) } catch (_: IllegalArgumentException) { accessory }
        refresh()
    }

    private fun refreshHintOnly() {
        val v = SafetyPolicy.checkJob(spec(), if (a.snapshot.hasSample) a.snapshot.actualTempC else null)
            .filter { it.code != SafetyPolicy.HOT_BLEND }
        hint.text = v.firstOrNull()?.message ?: if (a.snapshot.hot && speed >= 4) tr("Contenu chaud : confirmation demandée") else ""
    }

    override fun onKnob(delta: Int): Boolean {
        change(selected, delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        val s = a.snapshot
        when {
            s.state == JobState.RUNNING -> a.machine.pause { a.refusal(it) }
            else -> start()
        }
        return true
    }
}
