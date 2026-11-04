package com.tapper.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
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
        val timerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timerRow.addView(TextView(this).apply { text = "Stop after (minutes, 0 = never): " })
        val timer = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.timerMinutes(this@MainActivity).toString())
            minEms = 4
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) {
                    Prefs.setTimerMinutes(this@MainActivity, e.toString().toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        timerRow.addView(timer)
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
        root.addView(status); root.addView(enable); root.addView(show); root.addView(timerRow); root.addView(help)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TapService.instance != null) "Service enabled ✔" else "Service not enabled"
    }
}
