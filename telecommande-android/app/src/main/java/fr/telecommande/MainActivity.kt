package fr.telecommande

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var recBanner: LinearLayout
    private lateinit var recText: TextView
    private lateinit var shortcutsBox: LinearLayout

    /** Enregistrement en cours d'un raccourci. */
    private var recTarget: Shortcut? = null
    private val recSteps = mutableListOf<String>()
    private var recLast = 0L
    private var lastHint = 0L

    private val stateListener: () -> Unit = { runOnUiThread { refreshStatus() } }

    private fun dp(v: Number) = Ui.dp(this, v)

    // ------------------------------------------------------------------ Cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Hid.addListener(stateListener)
        refreshStatus()
        if (hasConnectPermission()) Hid.start() else askPermissions()
        checkUpdate(silent = true)
    }

    override fun onResume() {
        super.onResume()
        Updates.resumePending(this)
        if (hasConnectPermission() && Hid.state == Hid.State.BT_OFF && Hid.isEnabled) Hid.start()
        refreshStatus()
    }

    override fun onDestroy() {
        Hid.removeListener(stateListener)
        super.onDestroy()
    }

    private fun hasConnectPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (hasConnectPermission()) Hid.start() else refreshStatus()
        }
    }

    /** Les touches de volume du téléphone règlent le volume de la TV. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "volup"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "voldown"
            else -> null
        }
        if (id != null && Prefs.phoneVolume && Hid.connected) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onKey(id, true)
            if (event.action == KeyEvent.ACTION_UP) onKey(id, false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ Touches

    private fun onKey(id: String, down: Boolean) {
        val k = Keys.get(id) ?: return
        if (down) {
            record("k:$id")
            if (!Hid.connected) hintNotConnected()
        }
        if (k.holdMs > 0) {
            if (down) Hid.tap(k)
            return
        }
        if (down) Hid.down(k) else Hid.up(k)
    }

    private fun hintNotConnected() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHint < 2500) return
        lastHint = now
        if (recTarget == null) toast("Pas connecté à la TV")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ Interface

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // Barre d'état
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(6), dp(6))
        }
        statusDot = View(this)
        bar.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        statusText = Ui.text(this, "", 14f, Ui.ICON).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        bar.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.setOnClickListener { onStatusClick() }
        bar.addView(iconAction(R.drawable.ic_bluetooth, "Appareils") { showDevices() })
        bar.addView(iconAction(R.drawable.ic_more, "Plus") { v -> showMenu(v) })
        root.addView(bar)

        // Bandeau d'enregistrement
        recBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFF4A1D1D.toInt(), dp(16).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(8))
            visibility = View.GONE
        }
        recText = Ui.text(this, "", 14f, Ui.ICON, true)
        recBanner.addView(recText)
        recBanner.addView(Ui.text(this, "Faites la manœuvre sur la télécommande ci-dessous : chaque touche est enregistrée (et envoyée à la TV).", 12.5f, 0xFFE0B4B4.toInt()))
        val recButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        recButtons.addView(flatButton("+ Pause 1 s") { recSteps += "w:1000"; recLast = 0; refreshRec() })
        recButtons.addView(flatButton("+ Texte") { askRecText() })
        recButtons.addView(flatButton("Annuler") { stopRecording(false) })
        recButtons.addView(flatButton("Terminer") { stopRecording(true) })
        recBanner.addView(recButtons)
        root.addView(recBanner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        // Corps de la télécommande
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.BODY, dp(40).toFloat())
            setPadding(dp(22), dp(22), dp(22), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val small = 52
        val big = 58

        body.addView(row(
            key(R.drawable.ic_power, "power", small),
            keyAction(R.drawable.ic_keyboard, small, "Clavier") { showKeyboard() },
        ))
        body.addView(row(
            key(R.drawable.ic_menu, "menu", small),
            key(R.drawable.ic_mic, "search", big, light = true),
            key(R.drawable.ic_settings, "settings", small),
        ), lp(top = 14))

        val dpad = DpadView(this) { id, down -> onKey(id, down) }
        body.addView(dpad, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(18) })

        body.addView(row(
            key(R.drawable.ic_back, "back", small),
            key(R.drawable.ic_home, "home", big, light = true),
            key(R.drawable.ic_tv, "tv", small),
        ))

        // Volume / muet / chaînes
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(key(R.drawable.ic_mute, "mute", 46, iconDp = 20))
            addView(key(R.drawable.ic_guide, "guide", 46, iconDp = 20),
                LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(14) })
        }
        body.addView(row(
            rocker(R.drawable.ic_add, "volup", R.drawable.ic_remove, "voldown"),
            middle,
            rocker(R.drawable.ic_up, "chup", R.drawable.ic_down, "chdown"),
        ), lp(top = 22))

        body.addView(row(
            key(R.drawable.ic_rew, "rew", 46, iconDp = 22),
            key(R.drawable.ic_play_pause, "playpause", 46, iconDp = 22),
            key(R.drawable.ic_ff, "ff", 46, iconDp = 22),
        ), lp(top = 20))

        // Raccourcis
        shortcutsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(shortcutsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        buildShortcuts()

        val holder = FrameLayout(this)
        holder.addView(body, FrameLayout.LayoutParams(dp(340).coerceAtMost(resources.displayMetrics.widthPixels - dp(28)), -2, Gravity.CENTER_HORIZONTAL))
        holder.setPadding(0, dp(6), 0, dp(28))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(holder) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Android 15 affiche l'appli bord à bord : on laisse la place aux barres du système
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return root
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun row(vararg v: View): LinearLayout = Ui.spread(this, *v).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun key(icon: Int, id: String, size: Int, light: Boolean = false, iconDp: Int = 24): View {
        val v = Ui.roundKey(this, icon, size, light, iconDp)
        Ui.bindKey(v, id) { k, d -> onKey(k, d) }
        return v
    }

    private fun keyAction(icon: Int, size: Int, desc: String, action: () -> Unit): View {
        val v = Ui.roundKey(this, icon, size)
        v.contentDescription = desc
        v.setOnClickListener { action() }
        return v
    }

    private fun iconAction(icon: Int, desc: String, action: (View) -> Unit): View {
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(Ui.TEXT2)
        iv.contentDescription = desc
        iv.setPadding(dp(10), dp(10), dp(10), dp(10))
        iv.setOnClickListener { action(it) }
        iv.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        return iv
    }

    /** Bascule verticale (volume ou chaîne). */
    private fun rocker(topIcon: Int, topId: String, bottomIcon: Int, bottomId: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.KEY, dp(30).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(140))
        }
        for ((icon, id) in listOf(topIcon to topId, bottomIcon to bottomId)) {
            val f = FrameLayout(this)
            val iv = ImageView(this).apply { setImageResource(icon); setColorFilter(Ui.ICON) }
            f.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            Ui.bindKey(f, id) { k, d -> onKey(k, d) }
            col.addView(f, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    private fun flatButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13.5f
        setTextColor(0xFFFFD6D6.toInt())
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(10), dp(10), dp(6))
        setOnClickListener { action() }
    }

    // ------------------------------------------------------------------ État

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val (color, txt) = when (Hid.state) {
            Hid.State.UNAVAILABLE -> 0xFFF28B82.toInt() to (Hid.message?.lineSequence()?.first() ?: "Bluetooth indisponible") + "\nToucher ici pour les détails / réessayer"
            Hid.State.BT_OFF -> 0xFF9AA0A6.toInt() to
                if (!hasConnectPermission()) "Autorisation Bluetooth nécessaire · toucher ici"
                else "Bluetooth désactivé · toucher pour l'activer"
            Hid.State.STARTING -> 0xFFFDD663.toInt() to (Hid.message ?: "Démarrage…")
            Hid.State.READY -> 0xFFFDD663.toInt() to ((Hid.message?.let { "$it\n" } ?: "") + "Non connecté · toucher pour choisir la TV")
            Hid.State.CONNECTING -> 0xFFFDD663.toInt() to "Connexion${Hid.message?.let { " à $it" } ?: ""}…"
            Hid.State.CONNECTED -> 0xFF81C995.toInt() to "Connecté à ${Hid.host?.name ?: Hid.host?.address ?: "la TV"}"
        }
        statusDot.background = Ui.oval(color)
        statusText.text = txt
    }

    private fun onStatusClick() {
        when {
            !hasConnectPermission() -> askPermissions()
            Hid.state == Hid.State.BT_OFF -> try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
            Hid.state == Hid.State.UNAVAILABLE -> dialog().setTitle("Bluetooth")
                .setMessage(Hid.message ?: "Bluetooth indisponible")
                .setPositiveButton("Réessayer") { _, _ -> Hid.retry() }
                .setNegativeButton("Fermer", null).show()
            else -> showDevices()
        }
    }

    // ------------------------------------------------------------------ Raccourcis

    private fun buildShortcuts() {
        shortcutsBox.removeAllViews()
        val grid = GridLayout(this).apply { columnCount = 2 }
        val cells = Shortcuts.list.map { s -> shortcutPill(s) } + addPill()
        for (cell in cells) {
            val p = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            p.width = 0
            p.height = dp(46)
            p.setMargins(dp(5), dp(5), dp(5), dp(5))
            grid.addView(cell, p)
        }
        shortcutsBox.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    private fun shortcutPill(s: Shortcut): View = TextView(this).apply {
        text = s.name
        gravity = Gravity.CENTER
        textSize = 15f
        maxLines = 1
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(if (s.color == 0xFFFFFFFF.toInt()) 0xFF202124.toInt() else Ui.onColor(s.color))
        background = Ui.rounded(s.color, dp(23).toFloat())
        if (s.steps.isEmpty()) alpha = 0.6f
        setOnClickListener {
            if (Prefs.vibrate) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            runShortcut(s)
        }
        setOnLongClickListener { shortcutOptions(s); true }
    }

    private fun addPill(): View = TextView(this).apply {
        text = "+  Ajouter"
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(Ui.TEXT2)
        background = Ui.rounded(0x00000000, dp(23).toFloat(), Ui.TEXT2, dp(1))
        setOnClickListener { newShortcut() }
    }

    private fun runShortcut(s: Shortcut) {
        if (recTarget != null) { toast("Terminez d'abord l'enregistrement en cours"); return }
        if (s.steps.isEmpty()) {
            dialog().setTitle(s.name)
                .setMessage("Ce raccourci est encore vide.\n\nEnregistrez une fois la manœuvre qui ouvre l'appli sur votre TV (par exemple : Accueil, flèches jusqu'à l'icône, OK). Ensuite, un seul appui suffira.")
                .setPositiveButton("Enregistrer") { _, _ -> startRecording(s) }
                .setNegativeButton("Plus tard", null).show()
            return
        }
        if (!Hid.connected) { toast("Pas connecté à la TV"); return }
        toast(s.name)
        Hid.play(s.steps.toList())
    }

    private fun newShortcut() {
        val input = EditText(this).apply { hint = "Nom (ex. : Netflix, Molotov, Plex…)"; setSingleLine() }
        dialog().setTitle("Nouveau raccourci").setView(padded(input))
            .setPositiveButton("Enregistrer la séquence") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Raccourci" }
                val s = Shortcut(name, Shortcuts.colors[Shortcuts.list.size % Shortcuts.colors.size], mutableListOf())
                Shortcuts.list += s
                Shortcuts.save()
                buildShortcuts()
                startRecording(s)
            }
            .setNegativeButton("Annuler", null).show()
        showIme(input)
    }

    private fun shortcutOptions(s: Shortcut) {
        val i = Shortcuts.list.indexOf(s)
        val opts = arrayOf("Enregistrer la séquence à nouveau", "Modifier les étapes", "Renommer", "Couleur",
            "Déplacer avant", "Déplacer après", "Supprimer")
        dialog().setTitle(s.name).setItems(opts) { _, which ->
            when (which) {
                0 -> startRecording(s)
                1 -> editSteps(s)
                2 -> rename(s)
                3 -> pickColor(s)
                4 -> if (i > 0) { Shortcuts.list.removeAt(i); Shortcuts.list.add(i - 1, s); Shortcuts.save(); buildShortcuts() }
                5 -> if (i < Shortcuts.list.size - 1) { Shortcuts.list.removeAt(i); Shortcuts.list.add(i + 1, s); Shortcuts.save(); buildShortcuts() }
                6 -> dialog().setMessage("Supprimer « ${s.name} » ?")
                    .setPositiveButton("Supprimer") { _, _ -> Shortcuts.list.remove(s); Shortcuts.save(); buildShortcuts() }
                    .setNegativeButton("Annuler", null).show()
            }
        }.show()
    }

    private fun rename(s: Shortcut) {
        val input = EditText(this).apply { setText(s.name); setSingleLine(); selectAll() }
        dialog().setTitle("Renommer").setView(padded(input))
            .setPositiveButton("OK") { _, _ ->
                s.name = input.text.toString().trim().ifEmpty { s.name }
                Shortcuts.save(); buildShortcuts()
            }.setNegativeButton("Annuler", null).show()
        showIme(input)
    }

    private fun pickColor(s: Shortcut) {
        val grid = GridLayout(this).apply { columnCount = 5; setPadding(dp(16), dp(16), dp(16), dp(8)) }
        val dlg = dialog().setTitle("Couleur").setView(grid).setNegativeButton("Annuler", null).create()
        for (c in Shortcuts.colors) {
            val v = View(this)
            v.background = Ui.rounded(c, dp(22).toFloat(), Ui.TEXT2, if (c == s.color) dp(3) else dp(1))
            v.setOnClickListener { s.color = c; Shortcuts.save(); buildShortcuts(); dlg.dismiss() }
            grid.addView(v, GridLayout.LayoutParams().apply { width = dp(44); height = dp(44); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        dlg.show()
    }

    private fun editSteps(s: Shortcut) {
        val input = EditText(this).apply {
            setText(s.steps.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            gravity = Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
        }
        val help = Ui.text(this,
            "Une étape par ligne :\n" +
            "  k:touche   appui (home, back, up, down, left, right, ok, search, menu, settings, playpause…)\n" +
            "  w:1500     pause en millisecondes\n" +
            "  t:texte    tape du texte\n\n" +
            "Exemple : k:home  ·  w:1000  ·  k:down  ·  k:right  ·  k:ok", 12.5f, Ui.TEXT2)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(help); addView(input) }
        dialog().setTitle("Étapes de « ${s.name} »").setView(padded(box))
            .setPositiveButton("Enregistrer") { _, _ ->
                s.steps = input.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                    when {
                        line.startsWith("k:") || line.startsWith("w:") || line.startsWith("t:") -> line
                        Keys.get(line) != null -> "k:$line"
                        line.toIntOrNull() != null -> "w:$line"
                        else -> "t:$line"
                    }
                }.toMutableList()
                Shortcuts.save(); buildShortcuts()
            }
            .setNeutralButton("Tester") { _, _ ->
                if (Hid.connected) Hid.play(input.text.lines().map { it.trim() }.filter { it.isNotEmpty() }) else toast("Pas connecté à la TV")
            }
            .setNegativeButton("Annuler", null).show()
    }

    // ------------------------------------------------------------------ Enregistrement

    private fun startRecording(s: Shortcut) {
        recTarget = s
        recSteps.clear()
        recLast = 0
        recBanner.visibility = View.VISIBLE
        refreshRec()
        if (!Hid.connected) toast("Astuce : connectez d'abord la TV pour voir le résultat en direct")
    }

    private fun record(step: String) {
        if (recTarget == null) return
        val now = SystemClock.elapsedRealtime()
        // Une attente volontaire (appli qui se charge…) est gardée dans la séquence
        if (recLast > 0 && step.startsWith("k:")) {
            val gap = now - recLast
            if (gap > 1500) recSteps += "w:${(gap - Prefs.stepDelay).coerceIn(500, 4000)}"
        }
        recSteps += step
        recLast = now
        refreshRec()
    }

    private fun refreshRec() {
        val s = recTarget ?: return
        val last = recSteps.takeLast(4).joinToString(" › ") { Shortcuts.describe(it) }
        recText.text = "●  Enregistrement de « ${s.name} » : ${recSteps.size} étape(s)" + if (last.isNotEmpty()) "\n$last" else ""
    }

    private fun askRecText() {
        val input = EditText(this).apply { hint = "Texte à taper sur la TV"; setSingleLine() }
        dialog().setTitle("Ajouter du texte").setView(padded(input))
            .setPositiveButton("Ajouter") { _, _ ->
                val t = input.text.toString()
                if (t.isNotEmpty()) {
                    recSteps += "t:$t"
                    recLast = SystemClock.elapsedRealtime()
                    Hid.type(t)
                    refreshRec()
                }
            }.setNegativeButton("Annuler", null).show()
        showIme(input)
    }

    private fun stopRecording(save: Boolean) {
        val s = recTarget ?: return
        if (save) {
            if (recSteps.isEmpty()) { toast("Aucune touche enregistrée"); return }
            s.steps = recSteps.toMutableList()
            Shortcuts.save()
            toast("« ${s.name} » enregistré (${recSteps.size} étapes)")
        }
        recTarget = null
        recSteps.clear()
        recBanner.visibility = View.GONE
        buildShortcuts()
    }

    // ------------------------------------------------------------------ Clavier

    private fun showKeyboard() {
        val input = EditText(this).apply {
            hint = "Tapez : le texte s'écrit en direct sur la TV"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s == null || kbMute) return
                // Remplacement de [before] caractères par [count] nouveaux
                val back = Keys.get("backspace")!!
                repeat(before) { Hid.tap(back) }
                if (count > 0) Hid.type(s.subSequence(start, start + count).toString())
            }
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { enter(input); true } else false
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(Button(this).apply { text = "⌫"; setOnClickListener { Keys.get("backspace")?.let { Hid.tap(it) } } })
        buttons.addView(Button(this).apply { text = "Entrée"; setOnClickListener { enter(input) } })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(input); addView(buttons) }
        dialog().setTitle("Clavier").setView(padded(box)).setPositiveButton("Fermer", null).show()
        showIme(input)
    }

    /** Vrai pendant que l'on vide le champ : ne rien envoyer à la TV. */
    private var kbMute = false

    private fun enter(input: EditText) {
        onKey("ok", true); onKey("ok", false)
        // Vider le champ sans effacer le texte déjà envoyé sur la TV
        kbMute = true
        input.setText("")
        kbMute = false
    }

    // ------------------------------------------------------------------ Appareils

    private fun showDevices() {
        if (!hasConnectPermission()) { askPermissions(); return }
        val a = Hid.btAdapter ?: return toast("Pas de Bluetooth")
        if (!a.isEnabled) {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val phoneName = try { a.name } catch (_: Exception) { null } ?: "ce téléphone"
        box.addView(Ui.text(this,
            "Première fois :\n" +
            "1. Touchez « Rendre visible ».\n" +
            "2. Sur la TV : Paramètres › Télécommandes et accessoires › Associer un accessoire.\n" +
            "3. Choisissez « $phoneName » et acceptez l'association sur les deux appareils.\n\n" +
            "Ensuite la connexion se fait toute seule à l'ouverture de l'appli. " +
            "Si la TV est déjà associée, touchez-la ci-dessous.", 13.5f, Ui.TEXT2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val scanInfo = Ui.text(this, "", 12.5f, Ui.TEXT2)
        box.addView(scanInfo)

        val found = linkedMapOf<String, BluetoothDevice>()
        lateinit var dlg: AlertDialog

        fun fill() {
            list.removeAllViews()
            val bonded = try { a.bondedDevices.toList() } catch (_: Exception) { emptyList() }
            val all = (bonded + found.values.filter { f -> bonded.none { it.address == f.address } })
                .sortedWith(compareByDescending<BluetoothDevice> { it.address == Hid.host?.address }
                    .thenByDescending { it.address == Prefs.lastDevice }
                    .thenByDescending { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO })
            if (all.isEmpty()) list.addView(Ui.text(this, "Aucun appareil associé pour l'instant.", 14f, Ui.ICON))
            for (dev in all) {
                val bonded1 = dev.bondState == BluetoothDevice.BOND_BONDED
                val isHost = dev.address == Hid.host?.address
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    addView(Ui.text(this@MainActivity, dev.name ?: dev.address, 16f, if (isHost) 0xFF81C995.toInt() else Ui.ICON, true))
                    addView(Ui.text(this@MainActivity, when {
                        isHost -> "Connecté · toucher pour déconnecter"
                        bonded1 -> "Associé · toucher pour connecter"
                        else -> "Non associé · toucher pour associer"
                    }, 12.5f, Ui.TEXT2))
                }
                item.setOnClickListener {
                    when {
                        isHost -> { Hid.disconnect(); dlg.dismiss() }
                        bonded1 -> { Hid.connect(dev); dlg.dismiss() }
                        else -> {
                            try { a.cancelDiscovery() } catch (_: Exception) {}
                            try { dev.createBond() } catch (_: Exception) {}
                            toast("Association… acceptez sur la TV")
                        }
                    }
                }
                list.addView(item)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = deviceExtra(intent) ?: return
                        if (dev.name != null) { found[dev.address] = dev; fill() }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanInfo.text = "Recherche terminée."; fill() }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev = deviceExtra(intent) ?: return
                        fill()
                        if (dev.bondState == BluetoothDevice.BOND_BONDED) { Hid.connect(dev); dlg.dismiss() }
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })

        fill()
        val scroll = ScrollView(this).apply { addView(padded(box)) }
        dlg = dialog().setTitle("Connexion à la TV").setView(scroll)
            .setPositiveButton("Rendre visible", null)
            .setNeutralButton("Rechercher", null)
            .setNegativeButton("Fermer", null)
            .create()
        dlg.setOnDismissListener {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            try { a.cancelDiscovery() } catch (_: Exception) {}
        }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            } catch (_: Exception) { toast("Impossible de rendre le téléphone visible") }
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (Build.VERSION.SDK_INT < 31 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC)
                return@setOnClickListener
            }
            try {
                a.cancelDiscovery()
                if (a.startDiscovery()) scanInfo.text = "Recherche en cours… (la TV doit être visible)"
                else scanInfo.text = "La recherche n'a pas pu démarrer."
            } catch (_: SecurityException) { askPermissions() }
        }
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    // ------------------------------------------------------------------ Menu et réglages

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Réglages")
        m.menu.add(0, 2, 1, "Aide")
        m.menu.add(0, 4, 2, "Rechercher une mise à jour")
        if (Hid.connected) m.menu.add(0, 3, 3, "Déconnecter")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showSettings()
                2 -> showHelp()
                3 -> Hid.disconnect()
                4 -> checkUpdate(silent = false)
            }
            true
        }
        m.show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val vol = CheckBox(this).apply { text = "Les touches de volume du téléphone règlent la TV"; isChecked = Prefs.phoneVolume }
        val vib = CheckBox(this).apply { text = "Vibrer à chaque appui"; isChecked = Prefs.vibrate }
        val delayLabel = Ui.text(this, "", 14f, Ui.ICON)
        fun label(v: Int) { delayLabel.text = "Pause entre les étapes des raccourcis : $v ms" }
        val seek = SeekBar(this).apply {
            max = 15 // 100 à 1600 ms par pas de 100
            progress = ((Prefs.stepDelay - 100) / 100).coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { label(100 + p * 100) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        label(Prefs.stepDelay)
        box.addView(vol); box.addView(vib)
        box.addView(delayLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        box.addView(seek)
        box.addView(Ui.text(this, "Augmentez la pause si votre TV est lente et que les raccourcis « ratent » une touche.", 12.5f, Ui.TEXT2))
        dialog().setTitle("Réglages").setView(padded(box))
            .setPositiveButton("OK") { _, _ ->
                Prefs.phoneVolume = vol.isChecked
                Prefs.vibrate = vib.isChecked
                Prefs.stepDelay = 100 + seek.progress * 100
            }.setNegativeButton("Annuler", null).show()
    }

    private fun showHelp() {
        dialog().setTitle("Aide").setMessage(
            "• Le téléphone se connecte à la TV en Bluetooth comme une vraie télécommande (aucun Wi-Fi nécessaire).\n\n" +
            "• Maintenez une touche pour la répéter (flèches, volume).\n\n" +
            "• Réglages (roue dentée) = appui long sur Accueil : ouvre le panneau de réglages rapides de Google TV.\n\n" +
            "• Micro : ouvre l'Assistant / la recherche de la TV. La voix passe par la TV, pas par le téléphone ; utilisez le clavier pour taper votre recherche.\n\n" +
            "• Raccourcis d'applis : appuyez sur « + Ajouter », puis faites une fois la manœuvre qui ouvre l'appli (Accueil, flèches, OK…). L'appli la rejoue ensuite en un appui. " +
            "Astuce : placez vos applis favorites en tête de la rangée d'applis de la TV, la séquence restera la même.\n\n" +
            "• Appui long sur un raccourci : renommer, couleur, modifier les étapes, supprimer.\n\n" +
            "• Clavier : disposition QWERTY (celle d'Android TV par défaut) ; les accents sont retirés."
        ).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------ Mises à jour

    private fun checkUpdate(silent: Boolean) {
        if (!silent) toast("Recherche d'une mise à jour…")
        Updates.check(this) { u, err ->
            if (isFinishing || isDestroyed) return@check
            when {
                u != null -> dialog().setTitle("Mise à jour disponible")
                    .setMessage("La version ${u.version} est disponible (vous avez la ${Updates.current(this)}).\n\nElle va être téléchargée puis installée par-dessus : vos raccourcis sont conservés.")
                    .setPositiveButton("Installer") { _, _ -> Updates.downloadAndInstall(this, u) }
                    .setNegativeButton("Plus tard", null).show()
                silent -> {}
                err != null -> toast("Vérification impossible : $err")
                else -> toast("Vous avez la dernière version (${Updates.current(this)})")
            }
        }
    }

    // ------------------------------------------------------------------ Outils

    private fun dialog() = AlertDialog.Builder(this, R.style.Dialog)

    private fun padded(v: View): View = FrameLayout(this).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(v)
    }

    private fun showIme(input: EditText) {
        input.requestFocus()
        input.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_LOC = 2
    }
}
