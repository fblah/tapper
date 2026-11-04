package com.tapper.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.app.AlertDialog
import android.widget.Toast
import org.json.JSONObject
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private companion object { const val REQ_EXPORT = 1; const val REQ_IMPORT = 2 }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.START }
        val enable = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val show = Button(this).apply {
            text = "Show floating controls"
            setOnClickListener { TapService.instance?.showOverlay() }
        }
        val timerRow = numberRow("Stop after (minutes, 0 = never): ", Prefs.timerMinutes(this)) {
            Prefs.setTimerMinutes(this, it)
        }
        val sequence = CheckBox(this).apply {
            text = "Sequence mode (buttons tap one after another, in number order)"
            isChecked = Prefs.sequence(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setSequence(this@MainActivity, on) }
        }
        val cyclesRow = numberRow("Sequence cycles (0 = forever): ", Prefs.cycles(this)) {
            Prefs.setCycles(this, it)
        }
        val profileName = EditText(this).apply { hint = "Profile name (e.g. game name)" }
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Save"
                setOnClickListener {
                    val n = profileName.text.toString().trim()
                    if (n.isEmpty()) toast("Enter a profile name") else { Profiles.save(this@MainActivity, n); toast("Saved \"$n\"") }
                }
            })
            addView(Button(context).apply { text = "Load"; setOnClickListener { pickProfile(delete = false) } })
            addView(Button(context).apply { text = "Delete"; setOnClickListener { pickProfile(delete = true) } })
        }
        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Export file"
                setOnClickListener {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "tapper-profile.json")
                    }, REQ_EXPORT)
                }
            })
            addView(Button(context).apply {
                text = "Import file"
                setOnClickListener {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }, REQ_IMPORT)
                }
            })
        }
        val help = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = """
                How to use
                1. Enable "Tapper" under Accessibility > Installed apps. Android requires this so the app can perform taps for you.
                2. A floating control bar appears. Press + to add a tap button.
                3. While stopped, drag a button over what you want tapped. Tap a button to edit its interval (ms) or delete it.
                4. Press ▶ on the bar. Each button now taps itself at its own interval. Press ■ to stop and edit again.
                5. ✕ hides the controls; use "Show floating controls" here to bring them back.
            """.trimIndent()
        }
        root.addView(status); root.addView(enable); root.addView(show); root.addView(timerRow); root.addView(sequence); root.addView(cyclesRow)
        root.addView(profileName); root.addView(profileRow); root.addView(fileRow)
        root.addView(help)
        setContentView(root)
    }

    private fun numberRow(label: String, initial: Int, onChange: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = label })
            addView(EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(initial.toString())
                minEms = 4
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(e: Editable?) { onChange(e.toString().toIntOrNull() ?: 0) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            })
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
        status.text = if (TapService.instance != null) "Service enabled ✔" else "Service not enabled"
    }
}
