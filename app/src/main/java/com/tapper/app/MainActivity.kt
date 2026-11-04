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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private companion object { const val REQ_EXPORT = 1; const val REQ_IMPORT = 2 }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(32))
        }

        // Header
        root.addView(label("Tapper", 32f, Ui.TEXT, bold = true))
        root.addView(label("Floating tap & swipe buttons on a timer", 14f, Ui.MUTED))

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

        // Help
        val help = uiCard()
        help.addView(label("How it works", 18f, Ui.TEXT, bold = true))
        listOf(
            "1  Turn on Tapper in Accessibility settings (tap the status pill). Android requires this for any app that taps for you.",
            "2  Tap “Show floating controls”. On the bar: ＋ adds a tap button, ⇄ adds a swipe (drag its teal end marker).",
            "3  Drag buttons onto what you want tapped. Tap a button to set its interval, repeat count, or delete it.",
            "4  Press play on the bar. Press stop to edit again. You can also use the Tapper Quick Settings tile."
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
        val on = TapService.instance != null
        status.text = if (on) "●  Service active" else "●  Service off — tap to enable"
        status.setTextColor(if (on) Ui.OK else Ui.WARN)
        status.background = shape(if (on) Color.argb(40, 52, 211, 153) else Color.argb(40, 251, 191, 36), 20)
    }
}
