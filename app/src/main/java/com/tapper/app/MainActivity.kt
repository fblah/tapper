package com.tapper.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import java.io.File
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private companion object { const val REQ_EXPORT = 1; const val REQ_IMPORT = 2 }

    private lateinit var status: TextView
    private lateinit var updStatus: TextView
    private lateinit var updButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private var available: Updater.Release? = null
    private var pendingApk: File? = null
    private var busy = false
    private var checkedOnLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(32))
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.app_hero)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Ui.SURFACE, 16)
            clipToOutline = true
        }, lp(px(68), px(68), end = 16))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Tapper", 32f, Ui.TEXT, bold = true))
        titles.addView(label("Floating tap & swipe buttons on a timer", 14f, Ui.MUTED))
        header.addView(titles)
        root.addView(header)

        status = label("", 13f, Ui.TEXT, bold = true).apply {
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(status, lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 16))
        root.addView(uiButton("Show floating controls") { showControls() }, lp(top = 16))

        // Run settings
        val run = uiCard()
        run.addView(label("Run settings", 18f, Ui.TEXT, bold = true))
        run.addView(settingRow("Stop after", "Minutes, 0 = never",
            numberField(Prefs.timerMinutes(this)) { Prefs.setTimerMinutes(this, it) }))
        val sequence = Switch(this).apply {
            isChecked = Prefs.sequence(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setSequence(this@MainActivity, on) }
        }
        run.addView(settingRow("Sequence mode", "Buttons tap one after another, in number order", sequence))
        run.addView(settingRow("Sequence cycles", "0 = repeat forever",
            numberField(Prefs.cycles(this)) { Prefs.setCycles(this, it) }))
        run.addView(settingRow("Interval variation", "Random ± percent",
            numberField(Prefs.jitterPct(this)) { Prefs.setJitterPct(this, it) }))
        run.addView(settingRow("Position offset", "Random ± pixels",
            numberField(Prefs.jitterPx(this)) { Prefs.setJitterPx(this, it) }))
        root.addView(run, lp(top = 20))

        // Profiles
        val prof = uiCard()
        prof.addView(label("Profiles", 18f, Ui.TEXT, bold = true))
        prof.addView(label("Save your button layout and settings per game.", 12f, Ui.MUTED))
        val name = uiField("", numeric = false, hint = "Profile name")
        prof.addView(name, lp(top = 12))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(uiButton("Save") {
            val n = name.text.toString().trim()
            if (n.isEmpty()) toast("Enter a profile name")
            else { Profiles.save(this, n); toast("Saved \"$n\"") }
        }, lp(0, top = 0, end = 8).apply { weight = 1f })
        row1.addView(uiButton("Load", Ui.Kind.SECONDARY) { pickProfile(delete = false) },
            lp(0, end = 8).apply { weight = 1f })
        row1.addView(uiButton("Delete", Ui.Kind.DANGER) { pickProfile(delete = true) },
            lp(0).apply { weight = 1f })
        prof.addView(row1, lp(top = 12))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(uiButton("Export file", Ui.Kind.SECONDARY) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "tapper-profile.json")
            }, REQ_EXPORT)
        }, lp(0, end = 8).apply { weight = 1f })
        row2.addView(uiButton("Import file", Ui.Kind.SECONDARY) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        }, lp(0).apply { weight = 1f })
        prof.addView(row2, lp(top = 8))
        root.addView(prof, lp(top = 16))

        // Updates
        val upd = uiCard()
        upd.addView(label("Updates", 18f, Ui.TEXT, bold = true))
        updStatus = label("", 13f, Ui.MUTED)
        upd.addView(updStatus, lp(top = 4))
        updButton = uiButton("Check for updates", Ui.Kind.SECONDARY) { onUpdateButton() }
        upd.addView(updButton, lp(top = 12))
        updStatus.text = "Installed: ${Updater.installedName(this)} (build ${Updater.installedBuild(this)})"
        root.addView(upd, lp(top = 16))

        // Help
        val help = uiCard()
        help.addView(label("How it works", 18f, Ui.TEXT, bold = true))
        listOf(
            "1  Turn on Tapper in Accessibility settings (tap the status pill). Android requires this for any app that taps for you.",
            "2  Tap “Show floating controls”. On the bar: ＋ adds a tap button, ⇄ adds a swipe (drag its teal end marker).",
            "3  Drag buttons onto what you want tapped. Tap a button to set its interval, repeat count, or delete it.",
            "4  Press play on the bar. Press stop to edit again. You can also use the Tapper Quick Settings tile.",
            "5  Record: press the red dot, do your taps and swipes, press stop (that tap isn't recorded). Press the replay button to play them back; long-press it to set how long to play (0 = unlimited) and the pause between repeats. Recording again replaces the old one.",
            "6  The arrow next to the grip collapses the bar to save space."
        ).forEach { help.addView(label(it, 13f, Ui.MUTED), lp(top = 10)) }
        root.addView(help, lp(top = 16))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })
    }

    private fun numberField(initial: Int, onChange: (Int) -> Unit): EditText =
        uiField(initial.toString()).apply {
            minEms = 3
            layoutParams = LinearLayout.LayoutParams(px(84), ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { onChange(e.toString().toIntOrNull() ?: 0) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

    private fun setUpdate(text: String, buttonText: String, enabled: Boolean = true) {
        updStatus.text = text
        updButton.text = buttonText
        updButton.isEnabled = enabled
    }

    private fun onUpdateButton() {
        val rel = available
        if (rel == null) checkForUpdates() else downloadAndInstall(rel)
    }

    private fun checkForUpdates() {
        if (busy) return
        busy = true
        setUpdate("Checking…", "Checking…", false)
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            ui.post {
                busy = false
                val installed = Updater.installedBuild(this)
                result.onSuccess { rel ->
                    if (rel != null && rel.build > installed) {
                        available = rel
                        val mb = if (rel.size > 0) " (%.1f MB)".format(rel.size / 1048576.0) else ""
                        setUpdate("Update available: ${rel.name}, build ${rel.build}$mb. You have build $installed.",
                            "Download & install")
                    } else {
                        available = null
                        setUpdate("You're up to date (${Updater.installedName(this)}, build $installed).", "Check for updates")
                    }
                }.onFailure {
                    available = null
                    setUpdate("Couldn't check for updates: ${it.message ?: it.javaClass.simpleName}", "Try again")
                }
            }
        }.start()
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        if (busy) return
        busy = true
        setUpdate("Downloading build ${rel.build}…", "Downloading…", false)
        Thread {
            val result = runCatching {
                Updater.download(this, rel) { pct ->
                    ui.post { updButton.text = if (pct >= 0) "Downloading… $pct%" else "Downloading…" }
                }
            }
            ui.post {
                busy = false
                result.onSuccess { apk ->
                    setUpdate("Downloaded build ${rel.build}.", "Install update")
                    if (Updater.canInstall(this)) Updater.install(this, apk)
                    else {
                        pendingApk = apk
                        toast("Allow Tapper to install apps, then come back")
                        Updater.requestInstallPermission(this)
                    }
                }.onFailure {
                    setUpdate("Download failed: ${it.message ?: it.javaClass.simpleName}", "Try again")
                }
            }
        }.start()
    }

    private fun showControls() {
        val svc = TapService.instance
        if (svc == null) {
            toast("Turn on Tapper in Accessibility settings first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else svc.showOverlay()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun pickProfile(delete: Boolean) {
        val names = Profiles.names(this)
        if (names.isEmpty()) { toast("No saved profiles"); return }
        AlertDialog.Builder(this)
            .setTitle(if (delete) "Delete profile" else "Load profile")
            .setItems(names.toTypedArray()) { _, i ->
                if (delete) Profiles.delete(this, names[i])
                else { Profiles.load(this, names[i]); toast("Loaded \"${names[i]}\"") }
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == REQ_EXPORT) {
                contentResolver.openOutputStream(uri)!!.use { it.write(Profiles.snapshot(this).toString(2).toByteArray()) }
                toast("Exported")
            } else if (requestCode == REQ_IMPORT) {
                val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                Profiles.apply(this, JSONObject(text))
                toast("Imported")
            }
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        pendingApk?.let { apk ->
            if (Updater.canInstall(this)) { pendingApk = null; Updater.install(this, apk) }
        }
        if (!checkedOnLaunch) { checkedOnLaunch = true; checkForUpdates() }
        val on = TapService.instance != null
        status.text = if (on) "●  Service active" else "●  Service off — tap to enable"
        status.setTextColor(if (on) Ui.OK else Ui.WARN)
        status.background = shape(if (on) Color.argb(40, 52, 211, 153) else Color.argb(40, 251, 191, 36), 20)
    }
}
