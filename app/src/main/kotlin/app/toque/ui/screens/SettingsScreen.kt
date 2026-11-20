package app.toque.ui.screens

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.core.photos.SharedAlbum
import app.toque.core.time.ZoneLookup
import app.toque.core.update.Release
import app.toque.recipes.CookomixSync
import app.toque.recipes.LidlSync
import app.toque.recipes.OnlineSync
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.PhotoFrame
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.update.Updater

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Réglages")
    private lateinit var modeRow: LinearLayout
    private lateinit var twinBox: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var syncText: TextView
    private lateinit var ckxText: TextView
    private var ckxBusy = false
    /** Une seule boucle de suivi par synchro, quel que soit le nombre d'appels (onShow, boutons…). */
    private val ckxPoll = Runnable { if (ckxText.isAttachedToWindow) refreshCookomix() }
    private val lidlPoll = Runnable { if (syncText.isAttachedToWindow) refreshSync() }
    private lateinit var bowlText: TextView
    private lateinit var timeText: TextView
    private lateinit var albumField: EditText
    private lateinit var albumText: TextView
    private var settingTime = false
    private var mode = a.prefs.mode
    private var syncing = false

    private enum class Upd { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }
    private val updater by lazy { Updater(a) }
    private lateinit var updText: TextView
    private lateinit var updButtons: LinearLayout
    private var upd = Upd.IDLE
    private var release: Release? = null
    private var updMessage = ""
    private var updPercent = 0

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        val columns = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        val right = Ui.vertical(c)
        columns.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        columns.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        // Cette page ne garde que les commandes et les états : les explications sont dans l'aide.
        col.addView(Ui.button(c, tr("Aide sur les fonctionnalités"), Ui.Style.SECONDARY, Icon.Kind.INFO, height = 52, textSize = 18f) { a.push(HelpScreen(a)) },
            Ui.lp(Ui.WRAP, Ui.WRAP, b = 14))
        col.addView(columns)

        // ---- Machine
        val machine = section(left, tr("Machine"), Icon.Kind.POT)
        modeRow = Ui.horizontal(c)
        machine.addView(modeRow, Ui.lp(t = 4))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, tr("Hôte"))
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(58), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(58)))
        machine.addView(twinBox, Ui.lp(t = 12))
        machine.addView(Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { applyMode() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        renderModes()

        // ---- Sécurité
        val safety = section(left, tr("Sécurité"), Icon.Kind.WARNING)
        safety.addView(toggle(tr("Exiger la détection du bol"), a.prefs.requireBowl) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        })
        bowlText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        safety.addView(bowlText, Ui.lp(t = 6))
        safety.addView(toggle(tr("Montée en vitesse progressive à chaud"), a.prefs.hotRamp) { on ->
            a.prefs.hotRamp = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 10))
        safety.addView(toggle(tr("Faire confirmer les réglages des recettes"), a.prefs.confirmImported) { a.prefs.confirmImported = it }, Ui.lp(t = 10))
        safety.addView(toggle(tr("Enchaîner les étapes machine des recettes"), a.prefs.chainSteps) { a.prefs.chainSteps = it }, Ui.lp(t = 10))
        safety.addView(toggle(tr("Sons"), a.prefs.sound) { a.prefs.sound = it }, Ui.lp(t = 10))

        // ---- Écran de veille
        val standby = section(left, tr("Écran de veille"), Icon.Kind.CLOCK)
        val sleepOptions = listOf("5" to tr("Après 5 min sans contact"), "10" to tr("Après 10 min sans contact"),
            "30" to tr("Après 30 min sans contact"), "0" to tr("Jamais"))
        standby.addView(dropdown(sleepOptions, a.prefs.screenSleepMinutes.toString()) { a.prefs.screenSleepMinutes = it.toInt() },
            Ui.lp(Ui.MATCH, Ui.u(58), t = 4))
        standby.addView(Ui.text(c, tr("Pendant la veille"), 18f, Ui.TEXT), Ui.lp(t = 14))
        val photoBox = Ui.vertical(c)
        photoBox.visibility = if (a.prefs.standbyPhotos) View.VISIBLE else View.GONE
        standby.addView(dropdown(listOf("clock" to tr("Heure et date sur fond noir"), "photos" to tr("Cadre photo (album Google Photos)")),
            if (a.prefs.standbyPhotos) "photos" else "clock") { choice ->
            a.prefs.standbyPhotos = choice == "photos"
            photoBox.visibility = if (choice == "photos") View.VISIBLE else View.GONE
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 6))
        standby.addView(photoBox)
        albumField = field(a.prefs.photoAlbum, tr("Lien de partage de l'album"))
        photoBox.addView(albumField, Ui.lp(Ui.MATCH, Ui.u(58), t = 12))
        photoBox.addView(note(tr("Toute personne ayant ce lien peut voir l'album.")), Ui.lp(t = 6))
        albumText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f); visibility = View.GONE }
        photoBox.addView(albumText, Ui.lp(t = 10))
        photoBox.addView(Ui.button(c, tr("Vérifier l'album"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 52, textSize = 18f) { checkAlbum() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        val seconds = (10..60 step 10).map { it.toString() to tr("Une photo toutes les %d secondes", it) }
        photoBox.addView(dropdown(seconds, a.prefs.photoSeconds.toString()) { a.prefs.photoSeconds = it.toInt() }, Ui.lp(Ui.MATCH, Ui.u(58), t = 14))
        val brightness = listOf(5, 10, 20, 40, 60, 80, 100).map { it.toString() to tr("Luminosité des photos : %d %%", it) }
        photoBox.addView(dropdown(brightness, a.prefs.photoBrightness.toString()) { a.prefs.photoBrightness = it.toInt() }, Ui.lp(Ui.MATCH, Ui.u(58), t = 8))
        photoBox.addView(Ui.button(c, tr("Lancer le diaporama"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 58) { startPhotos() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))

        // ---- Langue (titre bilingue : trouvable quelle que soit la langue affichée)
        val language = section(right, "Langue · Language", Icon.Kind.INFO)
        val options = listOf(I18n.AUTO to tr("Automatique (langue du système)")) + I18n.languages.map { it.code to it.name }
        language.addView(dropdown(options, a.prefs.language) { code ->
            a.prefs.language = code
            a.applyLanguage()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))

        // ---- Date et heure
        val time = section(right, tr("Date et heure"), Icon.Kind.CLOCK)
        val zones = listOf(ZoneLookup.AUTO to tr("Automatique (d'après la connexion Internet)")) +
            (ZoneLookup.COMMON + listOf(TimeSync.current(), a.prefs.timeZone))
                .filter { it != ZoneLookup.AUTO }.distinct().map { it to it.replace('_', ' ') }
        time.addView(dropdown(zones, a.prefs.timeZone) { z ->
            a.prefs.timeZone = z
            setClock()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))
        timeText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        time.addView(timeText, Ui.lp(t = 10))
        time.addView(Ui.button(c, tr("Mettre à l'heure"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) { setClock() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        refreshTime()

        // ---- Recettes Lidl
        val lidl = section(right, tr("Recettes Lidl"), Icon.Kind.SYNC)
        syncText = Ui.text(c, "", 16f, Ui.TEXT)
        lidl.addView(syncText)
        val b1 = Ui.horizontal(c)
        b1.addView(Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { sync(online = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        lidl.addView(b1, Ui.lp(t = 12))
        // L'un sous l'autre : côte à côte, la colonne est trop étroite et le second bouton se replie sur 4 lignes.
        lidl.addView(Ui.button(c, tr("Importer de l'appli d'origine"), Ui.Style.SECONDARY, height = 52, textSize = 18f) { sync(online = false) },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        lidl.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 52, textSize = 18f) { a.openVendorApp() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        lidl.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 10))

        // ---- Recettes Cookomix
        val ckx = section(right, tr("Recettes Cookomix"), Icon.Kind.SYNC)
        ckxText = Ui.text(c, "", 16f, Ui.TEXT)
        ckx.addView(ckxText)
        ckx.addView(Ui.button(c, tr("Synchroniser Cookomix"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { syncCookomix() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        ckx.addView(toggle(tr("Inclure les recettes adaptées"), a.prefs.cookomixAdapted) { a.prefs.cookomixAdapted = it }, Ui.lp(t = 10))
        ckx.addView(toggle(tr("Synchro Cookomix chaque nuit"), a.prefs.cookomixAutoSync) { a.prefs.cookomixAutoSync = it }, Ui.lp(t = 10))
        refreshCookomix()

        // ---- Mises à jour
        val updates = section(left, tr("Mises à jour"), Icon.Kind.INFO)
        updText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        updates.addView(updText)
        updButtons = Ui.horizontal(c)
        updates.addView(updButtons, Ui.lp(t = 12))
        Updater.onInstallError = { msg -> updateFailed(msg) }
        renderUpdate()

        // ---- Outils
        val tools = section(right, tr("Outils"), Icon.Kind.SETTINGS)
        tools.addView(link(tr("Diagnostic de la base moteur")) { a.push(DiagnosticsScreen(a)) })
        tools.addView(link(tr("Bouton rotatif")) { a.push(KnobScreen(a)) })
        tools.addView(link(tr("Journal")) { a.push(LogScreen(a)) })
        tools.addView(link(tr("Paramètres Android")) { a.openAndroidSettings() })
        tools.addView(link(tr("Revenir à l'appli d'origine")) { restoreVendor() })
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        tools.addView(Ui.text(c, "Toque $version", 14f, Ui.TEXT3), Ui.lp(t = 12))

        return Ui.scroll(c, col)
    }

    private fun section(parent: LinearLayout, title: String, icon: Icon.Kind): LinearLayout {
        val card = Ui.card(c, 24f)
        val head = Ui.horizontal(c)
        head.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head, Ui.lp(b = 10))
        parent.addView(card, Ui.lp(b = 16))
        return card
    }

    /** Texte d'explication sous un réglage. */
    private fun note(text: String): TextView = Ui.text(c, text, 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }

    private fun albumStatus(message: String, color: Int) {
        albumText.text = message
        albumText.setTextColor(color)
        albumText.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Enregistre le lien saisi ; faux (avec un message) s'il n'a pas la forme d'un lien de partage. */
    private fun saveAlbum(): Boolean {
        val typed = albumField.text.toString().trim()
        val code = SharedAlbum.code(typed)
        if (typed.isNotEmpty() && code == null) {
            albumStatus(tr("Lien non reconnu : il se termine par un code, comme photos.app.goo.gl/AbCd1234."), Ui.WARN)
            return false
        }
        a.prefs.photoAlbum = code.orEmpty()
        if (code != null && code != typed) albumField.setText(code)
        albumStatus("", Ui.TEXT2)
        return true
    }

    private fun checkAlbum() {
        if (!saveAlbum()) return
        val code = a.prefs.photoAlbum
        if (code.isEmpty()) return
        albumStatus(tr("Lecture de l'album…"), Ui.TEXT2)
        Thread {
            val (ok, message) = try {
                val album = PhotoFrame.album(code, fresh = true)
                true to if (album.title.isEmpty()) tr("Album lu : %d photos", album.photos.size) else tr("Album « %s » : %d photos", album.title, album.photos.size)
            } catch (e: SharedAlbum.UnreadableException) {
                false to tr("Aucune photo trouvée à cette adresse : vérifiez que l'album est bien partagé par lien.")
            } catch (e: Exception) {
                false to tr("Album injoignable : vérifiez le Wi-Fi et le lien (%s).", e.message ?: e.javaClass.simpleName)
            }
            a.runOnUiThread { albumStatus(message, if (ok) Ui.OK else Ui.WARN) }
        }.start()
    }

    private fun startPhotos() {
        if (!saveAlbum()) return
        if (a.prefs.photoAlbum.isEmpty()) a.toast(tr("Saisissez d'abord le lien de partage de l'album."), Ui.WARN)
        else a.startPhotoFrame()
    }

    private fun field(value: String, hint: String) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        var state = initial
        val sw = Ui.text(c, "", 16f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        fun paint() {
            sw.text = if (state) tr("Oui") else tr("Non")
            sw.setTextColor(if (state) 0xFF1A0E05.toInt() else Ui.TEXT2)
            sw.background = if (state) Ui.round(Ui.ACCENT, 24f) else Ui.round(Ui.SURFACE3, 24f)
        }
        paint()
        sw.setOnClickListener { state = !state; paint(); onChange(state) }
        row.addView(sw, LinearLayout.LayoutParams(Ui.u(84), Ui.u(46)))
        return row
    }

    private fun link(label: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(0, Ui.u(14), 0, Ui.u(14))
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        row.background = Ui.pressable(Ui.round(0, 8f), 8f)
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    /** Menu déroulant : la valeur choisie, et la liste qui s'ouvre en dessous. */
    private fun dropdown(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val box = Ui.horizontal(c)
        box.setPadding(Ui.u(16), 0, Ui.u(14), 0)
        box.background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
        var current = selected
        val label = Ui.text(c, options.firstOrNull { it.first == current }?.second ?: options.first().second, 19f, Ui.TEXT)
        box.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2).apply { rotation = 90f }, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)))
        box.isClickable = true
        box.setOnClickListener {
            val list = Ui.vertical(c)
            list.setPadding(0, Ui.u(6), 0, Ui.u(6))
            val scroll = ScrollView(c).apply {
                addView(list)
                background = Ui.round(Ui.SURFACE3, 16f, Ui.STROKE, 1f)
            }
            val height = if (options.size > 7) Ui.u(400) else ViewGroup.LayoutParams.WRAP_CONTENT
            val popup = PopupWindow(scroll, box.width, height, false)
            for ((code, name) in options) {
                val row = Ui.text(c, name, 19f, if (code == current) Ui.ACCENT else Ui.TEXT)
                row.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14))
                row.background = Ui.pressable(Ui.round(0, 0f), 0f)
                row.isClickable = true
                row.setOnClickListener {
                    popup.dismiss()
                    if (code != current) {
                        current = code
                        label.text = name
                        onPick(code)
                    }
                }
                list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            popup.setBackgroundDrawable(ColorDrawable(0))
            popup.isOutsideTouchable = true
            popup.elevation = Ui.uf(8f)
            popup.showAsDropDown(box, 0, Ui.u(6))
        }
        return box
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Prefs.Mode.values()) {
            modeRow.addView(Ui.toggleChip(c, when (m) {
                Prefs.Mode.DEMO -> tr("Démo")
                Prefs.Mode.TWIN -> tr("Jumeau (PC)")
                Prefs.Mode.HARDWARE -> tr("Machine réelle")
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        twinBox.visibility = if (mode == Prefs.Mode.TWIN) View.VISIBLE else View.GONE
    }

    private fun applyMode() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        if (mode == Prefs.Mode.HARDWARE && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.confirm(
                tr("Piloter la vraie machine"),
                tr("Toque va prendre le contrôle du moteur et de la chauffe à la place de l'appli d'origine.\n\n" +
                    "Premier essai : bol vide, couvercle fermé, à portée de l'interrupteur principal. " +
                    "Si quoi que ce soit semble anormal, coupez l'interrupteur."),
                tr("Continuer"), danger = true,
            ) { prepareHardware() }
            return
        }
        save()
    }

    private fun save() {
        a.prefs.mode = mode
        a.prefs.bootLaunch = true
        a.prefs.twinHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
        a.prefs.twinPort = port.text.toString().trim().toIntOrNull() ?: 8765
        a.machine.reconfigure { v -> if (v.ok) a.toast(tr("Mode : %s", mode.label), Ui.OK) else a.refusal(v) }
        a.refreshTitle()
    }

    private fun prepareHardware() {
        a.toast(tr("Vérifications…"))
        Thread {
            val problems = ArrayList<String>()
            if (!RootShell.available()) problems += tr("accès root refusé")
            val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
            if (problems.isEmpty() && sync.installed()) {
                if (sync.components().hardwareService != null && !sync.setVendorHardware(false))
                    problems += tr("impossible de couper l'appli d'origine du moteur")
                sync.forceStop()
            }
            a.runOnUiThread {
                if (problems.isNotEmpty()) {
                    a.confirm(tr("Machine réelle indisponible"), problems.joinToString("\n") { "• $it" }, "OK", tr("Fermer")) {}
                } else save()
            }
        }.start()
    }

    private fun restoreVendor() {
        a.confirm(
            tr("Revenir à l'appli d'origine"),
            tr("Toque libère le moteur, rend l'accès à l'appli Monsieur Cuisine d'origine et passe en mode Démo. " +
                "Toque ne s'ouvrira plus au démarrage du robot."),
            tr("Revenir"),
        ) {
            a.prefs.mode = Prefs.Mode.DEMO
            a.prefs.bootLaunch = false
            a.machine.reconfigure {
                Thread {
                    val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
                    sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    a.runOnUiThread {
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else a.toast(tr("Appli d'origine introuvable"), Ui.WARN)
                    }
                }.start()
            }
        }
    }

    private fun sync(online: Boolean) {
        if (online && OnlineSync.isRunning) {
            a.toast(tr("Synchronisation déjà en cours"), Ui.WARN)
            refreshSync()
            return
        }
        if (syncing) return
        if (!online && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast(tr("Disponible une fois Toque installé sur le robot (mode Machine réelle)"), Ui.WARN)
            return
        }
        syncing = true
        syncText.text = tr("Synchronisation en cours… (quelques minutes)")
        Thread {
            val r = if (online) OnlineSync(a, a.prefs, a.app.recipes, a.app.log).sync { msg -> a.runOnUiThread { syncText.text = msg } }
            else LidlSync(a, a.prefs, a.app.recipes, a.app.log).importNow { msg -> a.runOnUiThread { syncText.text = msg } }
            a.runOnUiThread {
                syncing = false
                refreshSync()
                a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN)
            }
        }.start()
    }

    private fun refreshTime(message: String? = null) {
        val zone = TimeSync.current()
        val mode = if (a.prefs.timeZone == ZoneLookup.AUTO) tr("automatique") else tr("choisi à la main")
        val shown = zone.replace('_', ' ')
        timeText.text = (if (TimeSync.trusted) tr("Il est %s · fuseau %s (%s)", TimeSync.clock(), shown, mode)
            else tr("Heure non confirmée · fuseau %s (%s)", shown, mode)) + (message?.let { "\n" + it } ?: "")
    }

    private fun setClock() {
        if (settingTime) return
        settingTime = true
        timeText.text = tr("Réglage de l'heure…")
        Thread {
            val r = try {
                TimeSync(a, a.prefs, a.app.log).run()
            } catch (e: Exception) {
                TimeSync.Result(false, TimeSync.current(), e.message ?: e.toString())
            }
            a.runOnUiThread {
                settingTime = false
                refreshTime(if (r.ok) null else r.message)
                if (r.ok) a.toast(r.message, Ui.OK)
            }
        }.start()
    }

    private fun refreshCookomix() {
        ckxText.removeCallbacks(ckxPoll)
        val resuming = a.prefs.cookomixSyncPending && app.toque.ToqueApp.resumesScheduled.get() > 0
        if (CookomixSync.isRunning || resuming) {
            // Synchro lancée avant d'ouvrir cet écran, de nuit, ou interrompue et sur le point de
            // reprendre toute seule : on suit sa progression.
            ckxText.text = CookomixSync.current.ifEmpty {
                if (CookomixSync.isRunning) tr("Synchronisation Cookomix en cours…") else tr("Reprise automatique dès que le réseau répond…")
            }
            ckxText.postDelayed(ckxPoll, 1_000)
            return
        }
        val last = a.prefs.cookomixLastResult
        ckxText.text = if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernière synchro le %s", last)
    }

    private fun syncCookomix() {
        if (CookomixSync.isRunning) {
            a.toast(tr("Synchronisation Cookomix déjà en cours"), Ui.WARN)
            refreshCookomix()
            return
        }
        if (ckxBusy) return
        ckxBusy = true
        ckxText.text = tr("Synchronisation Cookomix en cours…")
        Thread {
            val r = CookomixSync(a, a.prefs, a.app.recipes, a.app.log).sync { msg -> a.runOnUiThread { ckxText.text = msg } }
            a.runOnUiThread {
                ckxBusy = false
                refreshCookomix()
                refreshSync()
                a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN)
            }
        }.start()
    }

    private fun refreshSync() {
        syncText.removeCallbacks(lidlPoll)
        val resuming = a.prefs.lidlSyncPending && app.toque.ToqueApp.resumesScheduled.get() > 0
        if (OnlineSync.isRunning || resuming) {
            // Synchro lancée avant d'ouvrir cet écran, de nuit, ou interrompue et sur le point de
            // reprendre toute seule : on suit sa progression.
            syncText.text = OnlineSync.current.ifEmpty {
                if (OnlineSync.isRunning) tr("Synchronisation en cours… (quelques minutes)") else tr("Reprise automatique dès que le réseau répond…")
            }
            syncText.postDelayed(lidlPoll, 1_000)
            return
        }
        val last = a.prefs.lastSyncResult
        syncText.text = (if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernière synchro le %s", last)) +
            "\n" + tr("%d recettes dans Toque", a.app.recipes.count)
    }

    // ------------------------------------------------------------------ mises à jour

    private fun renderUpdate() {
        val r = release
        val installed = tr("Version installée : %s", updater.installedVersion)
        updText.setTextColor(if (upd == Upd.ERROR) Ui.WARN else if (upd == Upd.AVAILABLE) Ui.TEXT else Ui.TEXT2)
        updText.text = when (upd) {
            Upd.IDLE -> installed + "\n" + tr("Les nouvelles versions sont publiées sur GitHub.")
            Upd.CHECKING -> installed + "\n" + tr("Recherche sur GitHub…")
            Upd.UP_TO_DATE -> installed + "\n" + tr("Toque est à jour.")
            Upd.AVAILABLE -> installed + "\n" + tr("Nouvelle version disponible : %s", r?.version) + notes(r)
            Upd.DOWNLOADING -> tr("Téléchargement de la version %s… %d %%", r?.version, updPercent)
            Upd.INSTALLING -> tr("Installation de la version %s… Toque va redémarrer.\n" +
                "Si Android demande une confirmation, touchez « Installer ».", r?.version)
            Upd.ERROR -> installed + "\n" + updMessage
        }
        updButtons.removeAllViews()
        when (upd) {
            Upd.IDLE, Upd.UP_TO_DATE, Upd.ERROR ->
                updButtons.addView(Ui.button(c, tr("Rechercher une mise à jour"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { checkUpdate() })
            Upd.AVAILABLE -> {
                updButtons.addView(Ui.button(c, tr("Installer la version %s", r?.version), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { installUpdate() },
                    Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                updButtons.addView(Ui.button(c, tr("Plus tard"), Ui.Style.GHOST, height = 58) { upd = Upd.IDLE; renderUpdate() })
            }
            else -> {}
        }
    }

    private fun notes(r: Release?): String {
        val text = r?.notes?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4)?.joinToString("\n") ?: ""
        if (text.isEmpty()) return ""
        return "\n\n" + if (text.length > 280) text.substring(0, 280) + "…" else text
    }

    private fun checkUpdate() {
        if (upd == Upd.CHECKING || upd == Upd.DOWNLOADING || upd == Upd.INSTALLING) return
        upd = Upd.CHECKING
        renderUpdate()
        Thread {
            try {
                // Sans heure juste, GitHub refuse la connexion sécurisée : on tente d'abord le réglage.
                if (!TimeSync.trusted) try {
                    TimeSync(a, a.prefs, a.app.log).run()
                } catch (_: Exception) {
                }
                val r = updater.latest()
                a.runOnUiThread {
                    release = r
                    upd = if (updater.isNewer(r)) Upd.AVAILABLE else Upd.UP_TO_DATE
                    renderUpdate()
                    if (upd == Upd.AVAILABLE) installUpdate()
                }
            } catch (e: Updater.Failure) {
                a.runOnUiThread { upd = Upd.ERROR; updMessage = e.message ?: tr("Erreur"); renderUpdate() }
            } catch (e: Exception) {
                a.app.log.crash("maj", e)
                a.runOnUiThread { upd = Upd.ERROR; updMessage = tr("Erreur inattendue : %s", e.message); renderUpdate() }
            }
        }.start()
    }

    /** Propose l'installation dans une fenêtre (toujours visible, même si la carte est hors écran). */
    private fun installUpdate() {
        val r = release ?: return
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.confirm(
            tr("Version %s disponible", r.version),
            notes(r).trim().let { if (it.isEmpty()) "" else it + "\n\n" } +
                tr("Toque va la télécharger depuis GitHub, l'installer puis redémarrer (environ une minute). " +
                    "Le moteur reste arrêté pendant l'opération."),
            tr("Installer"), tr("Plus tard"), icon = Icon.Kind.SYNC,
        ) {
            a.machine.suspendPort { v ->
                if (!v.ok) { a.refusal(v); return@suspendPort }
                upd = Upd.DOWNLOADING
                updPercent = 0
                renderUpdate()
                Thread {
                    try {
                        val file = updater.download(r) { p -> a.runOnUiThread { updPercent = p; renderUpdate() } }
                        a.runOnUiThread { upd = Upd.INSTALLING; renderUpdate() }
                        updater.install(file)
                    } catch (e: Updater.Failure) {
                        a.runOnUiThread { updateFailed(e.message ?: tr("Erreur")) }
                    } catch (e: Exception) {
                        a.app.log.crash("maj", e)
                        a.runOnUiThread { updateFailed(tr("Erreur inattendue : %s", e.message)) }
                    }
                }.start()
            }
        }
    }

    private fun updateFailed(message: String) {
        a.machine.resumePort()
        upd = Upd.ERROR
        updMessage = message
        renderUpdate()
        a.toast(message, Ui.WARN)
    }

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        if (!syncing) refreshSync()
        if (::ckxText.isInitialized && !ckxBusy) refreshCookomix()
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (!s.hasSample) tr("Base non connectée") else if (s.bowlDetected) tr("Capteur : bol détecté") else tr("Capteur : bol non détecté")
    }
}
