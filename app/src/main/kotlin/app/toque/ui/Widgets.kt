package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr

/** Carte de réglage : libellé, grande valeur, boutons − / +, sélection pour le bouton rotatif. */
class ValueCard(c: Context, label: String, icon: Icon.Kind) : LinearLayout(c) {
    private val valueView = Ui.text(c, "", 64f, Ui.TEXT, Ui.LIGHT)
    private val unitView = Ui.text(c, "", 22f, Ui.TEXT2, Ui.LIGHT)
    private val subView = Ui.ellipsize(Ui.text(c, "", 16f, Ui.TEXT3))
    private val iconView = Icon(c, icon, Ui.TEXT2)
    private val minus: View
    private val plus: View
    private val valueRow: LinearLayout
    private val buttons: LinearLayout
    /** Molette, affichée à la place de la grande valeur et des boutons en mode molettes. */
    val dial = Dial(c)
    var onMinus: () -> Unit = {}
    var onPlus: () -> Unit = {}
    var onSelect: () -> Unit = {}
    /** Répétition à l'appui long (désactivée pendant une cuisson pour vitesse et température). */
    var repeatEnabled = true
    private var isSel = false

    init {
        orientation = VERTICAL
        setPadding(Ui.u(20), Ui.u(18), Ui.u(20), Ui.u(16))
        val head = Ui.horizontal(c)
        head.addView(iconView, LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(8) })
        head.addView(Ui.text(c, label.uppercase(), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f })
        addView(head)
        valueRow = Ui.horizontal(c, Gravity.BOTTOM)
        valueRow.addView(valueView)
        valueRow.addView(unitView, Ui.lp(Ui.WRAP, Ui.WRAP, l = 6, b = 10))
        addView(valueRow, Ui.lp(t = 10))
        addView(subView, Ui.lp(t = 2))
        dial.visibility = GONE
        dial.onTouchStart = { onSelect() }
        addView(dial, LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(6) })
        addView(Ui.spacer(c, h = 0, weight = 1f))
        buttons = Ui.horizontal(c)
        minus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 60) { onSelect(); onMinus() }
        plus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 60) { onSelect(); onPlus() }
        repeatOnHold(minus, { repeatEnabled }) { onMinus() }
        repeatOnHold(plus, { repeatEnabled }) { onPlus() }
        buttons.addView(minus, LayoutParams(0, Ui.u(60), 1f).apply { rightMargin = Ui.u(10) })
        buttons.addView(plus, LayoutParams(0, Ui.u(60), 1f))
        addView(buttons, Ui.lp(t = 14))
        isClickable = true
        setOnClickListener { onSelect() }
        refreshBg()
    }

    fun set(value: String, unit: String = "", sub: String = "", color: Int = Ui.TEXT) {
        valueView.text = value
        valueView.setTextColor(color)
        unitView.text = unit
        subView.text = sub
        dial.set(dial.position, value, unit, sub, color)
    }

    /** Position et couleur de la molette ([steps] positions). */
    fun setDial(steps: Int, position: Int, color: Int) {
        dial.steps = steps.coerceAtLeast(2)
        dial.color = color
        dial.set(position, valueView.text.toString(), unitView.text.toString(), subView.text.toString(), valueView.currentTextColor)
    }

    /** Bascule entre grande valeur + boutons − / + et molette tactile. */
    fun setDialMode(on: Boolean) {
        val vis = if (on) GONE else VISIBLE
        valueRow.visibility = vis
        subView.visibility = vis
        buttons.visibility = vis
        getChildAt(indexOfChild(dial) + 1).visibility = vis // espace extensible du mode boutons
        dial.visibility = if (on) VISIBLE else GONE
    }

    fun select(sel: Boolean) {
        if (sel == isSel) return
        isSel = sel
        refreshBg()
    }

    fun setEditable(on: Boolean) {
        Ui.setButtonEnabled(minus, on)
        Ui.setButtonEnabled(plus, on)
    }

    private fun refreshBg() {
        background = Ui.round(Ui.SURFACE, 26f, if (isSel) Ui.ACCENT else Ui.STROKE, if (isSel) 2.5f else 1f)
        iconView.color = if (isSel) Ui.ACCENT else Ui.TEXT2
    }

    companion object {
        /**
         * Répète l'action tant que le bouton reste appuyé (après 450 ms). S'arrête de lui-même si
         * le bouton n'est plus enfoncé ou après 40 répétitions, même si le relâchement est perdu.
         */
        fun repeatOnHold(v: View, enabled: () -> Boolean = { true }, action: () -> Unit) {
            var count = 0
            val r = object : Runnable {
                override fun run() {
                    if (!v.isPressed || !enabled() || count >= 40) return
                    count++
                    action()
                    v.postDelayed(this, 110)
                }
            }
            v.setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        count = 0
                        view.removeCallbacks(r)
                        view.postDelayed(r, 450)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(r)
                }
                false
            }
        }
    }
}

/** Anneau de température : valeur mesurée au centre, arc proportionnel, repère de consigne. */
class TempRing(c: Context) : View(c) {
    var actual = 0
    var target = 0
    var rpmLevel = 0
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun set(actual: Int, target: Int, rpmLevel: Int) {
        if (actual == this.actual && target == this.target && rpmLevel == this.rpmLevel) return
        this.actual = actual
        this.target = target
        this.rpmLevel = rpmLevel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val sw = size * 0.07f
        val cx = width / 2f
        val cy = height / 2f
        val rad = size / 2f - sw
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        arc.strokeWidth = sw
        arc.color = Ui.SURFACE3
        canvas.drawArc(r, 135f, 270f, false, arc)
        val frac = (actual / 130f).coerceIn(0f, 1f)
        arc.color = Ui.tempColor(actual)
        if (frac > 0f) canvas.drawArc(r, 135f, 270f * frac, false, arc)
        if (target > 0) {
            val a = Math.toRadians((135 + 270 * (target / 130f).coerceIn(0f, 1f)).toDouble())
            arc.strokeWidth = sw * 0.35f
            arc.color = Ui.TEXT
            val r1 = rad - sw * 0.9f
            val r2 = rad + sw * 0.9f
            canvas.drawLine(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), arc)
        }
        text.typeface = Ui.THIN
        text.color = Ui.TEXT
        text.textSize = size * 0.30f
        // 0 : la base ne donne pas de température à froid, ce n'est pas une mesure.
        if (actual > 0) canvas.drawText("$actual°", cx + size * 0.03f, cy + size * 0.08f, text)
        else canvas.drawText("—", cx, cy + size * 0.08f, text)
        text.typeface = Ui.REGULAR
        text.color = Ui.TEXT2
        text.textSize = size * 0.075f
        val caption = if (target > 0) tr("consigne %d °C", target) else if (actual > 0) tr("mesuré") else ""
        canvas.drawText(caption, cx, cy + size * 0.22f, text)
        // barres de vitesse réelle
        val bw = size * 0.035f
        val gap = size * 0.018f
        val total = 10 * bw + 9 * gap
        var x = cx - total / 2
        val base = cy + size * 0.40f
        for (i in 1..10) {
            arc.style = Paint.Style.FILL
            arc.color = if (i <= rpmLevel) Ui.COOL else Ui.SURFACE3
            val h = size * (0.03f + 0.006f * i)
            r.set(x, base - h, x + bw, base)
            canvas.drawRoundRect(r, bw / 3, bw / 3, arc)
            x += bw + gap
        }
        arc.style = Paint.Style.STROKE
    }
}

/**
 * Molette tactile (« potard ») : arc de 270° découpé en [steps] positions. On fait glisser le
 * curseur au doigt, ou on touche l'arc pour y sauter ; [onPick] reçoit la position visée et
 * l'écran décide de la valeur retenue (limites de sécurité comprises), puis appelle [set].
 * La valeur, son unité et une légende sont dessinées au centre.
 */
class Dial(c: Context) : View(c) {
    var steps = 2
    var position = 0
        private set
    var color = Ui.ACCENT
    var onPick: (Int) -> Unit = {}
    var onTouchStart: () -> Unit = {}
    /** Nombre de crans dont un même geste peut déplacer le curseur (0 : sans limite). */
    var maxStepsPerGesture = 0
    private var dragging = false
    private var startPosition = 0
    private var value = ""
    private var unit = ""
    private var sub = ""
    private var valueColor = Ui.TEXT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun set(position: Int, value: String, unit: String, sub: String, valueColor: Int) {
        this.position = position.coerceIn(0, steps - 1)
        this.value = value
        this.unit = unit
        this.sub = sub
        this.valueColor = valueColor
        invalidate()
    }

    private fun fraction(pos: Int) = if (steps <= 1) 0f else pos / (steps - 1).toFloat()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val sw = size * 0.075f
        val cx = width / 2f
        val cy = centerY()
        val rad = size / 2f - sw
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Ui.SURFACE3
        canvas.drawArc(r, 135f, 270f, false, paint)
        // graduations (si les positions ne sont pas trop serrées)
        if (steps in 3..21) {
            paint.strokeWidth = sw * 0.12f
            paint.color = Ui.STROKE
            for (i in 0 until steps) {
                val a = Math.toRadians((135 + 270 * fraction(i)).toDouble())
                val r1 = rad + sw * 0.75f
                val r2 = rad + sw * 1.05f
                canvas.drawLine(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                    cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), paint)
            }
        }
        val f = fraction(position)
        paint.strokeWidth = sw
        paint.color = color
        if (f > 0f) canvas.drawArc(r, 135f, 270f * f, false, paint)
        // curseur
        val a = Math.toRadians((135 + 270 * f).toDouble())
        val kx = cx + (rad * Math.cos(a)).toFloat()
        val ky = cy + (rad * Math.sin(a)).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Ui.TEXT
        canvas.drawCircle(kx, ky, sw * 0.95f, paint)
        paint.color = color
        canvas.drawCircle(kx, ky, sw * 0.5f, paint)
        // valeur au centre
        text.typeface = Ui.LIGHT
        text.color = valueColor
        text.textSize = size * (if (value.length > 4) 0.20f else 0.27f)
        canvas.drawText(value, cx, cy + size * 0.06f, text)
        text.typeface = Ui.REGULAR
        text.color = Ui.TEXT2
        text.textSize = size * 0.085f
        if (unit.isNotEmpty()) canvas.drawText(unit, cx, cy + size * 0.18f, text)
        text.color = Ui.TEXT3
        text.textSize = size * 0.075f
        if (sub.isNotEmpty()) canvas.drawText(sub, cx, cy + size * 0.40f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouchStart()
                startPosition = position
                dragging = onArc(e)
                if (dragging) pick(e)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) pick(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun centerY() = height / 2f + minOf(width, height) * 0.04f

    /** Le geste commence-t-il sur l'arc ? Toucher la valeur au centre ne règle rien. */
    private fun onArc(e: MotionEvent): Boolean {
        val size = minOf(width, height).toFloat()
        val arcRadius = size / 2f - size * 0.075f
        return Math.hypot((e.x - width / 2f).toDouble(), (e.y - centerY()).toDouble()) >= arcRadius * 0.6f
    }

    /** Angle du doigt → position ; dans l'ouverture du bas, on reste à l'extrémité la plus proche. */
    private fun pick(e: MotionEvent) {
        val deg = Math.toDegrees(Math.atan2((e.y - centerY()).toDouble(), (e.x - width / 2f).toDouble())).toFloat()
        var rel = (deg - 135f + 360f) % 360f // 0 = début de l'arc, 270 = fin
        if (rel > 270f) rel = if (rel < 315f) 270f else 0f
        var pos = Math.round(rel / 270f * (steps - 1))
        if (maxStepsPerGesture > 0) pos = pos.coerceIn(startPosition - maxStepsPerGesture, startPosition + maxStepsPerGesture)
        pos = pos.coerceIn(0, steps - 1)
        if (pos != position) onPick(pos)
    }
}

/** Bouton « maintenir pour Turbo ». */
class HoldButton(c: Context, label: String, private val onDown: () -> Unit, private val onUp: () -> Unit) : LinearLayout(c) {
    private val text: TextView

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER)
        setPadding(Ui.u(22), 0, Ui.u(22), 0)
        minimumHeight = Ui.u(64)
        background = Ui.round(Ui.SURFACE3, 32f)
        // Le texte est centré dans le bouton : l'éclair à sa gauche a son pendant invisible à droite.
        val iconWidth = Ui.u(26)
        val gap = Ui.u(16)
        addView(Icon(c, Icon.Kind.BOLT, Ui.WARN), LayoutParams(iconWidth, iconWidth).apply { rightMargin = gap })
        text = Ui.text(c, label, 20f, Ui.TEXT, Ui.MEDIUM)
        addView(text)
        addView(View(c), LayoutParams(iconWidth + gap, 1))
        isClickable = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                background = Ui.round(Ui.WARN, 32f)
                text.setTextColor(0xFF1A1405.toInt())
                onDown()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                background = Ui.round(Ui.SURFACE3, 32f)
                text.setTextColor(Ui.TEXT)
                onUp()
            }
        }
        return true
    }
}
