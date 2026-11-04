package com.tapper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Shows floating tap buttons (accessibility overlay windows) and injects taps at their centres
 * using dispatchGesture. While running, the overlays are not touchable so taps pass through.
 */
class TapService : AccessibilityService() {

    companion object {
        var instance: TapService? = null
            private set
        private const val MIN_INTERVAL_MS = 20L
        private const val TAP_DURATION_MS = 30L
    }

    private inner class Bubble(val target: Target) {
        fun finished() = target.repeat > 0 && count >= target.repeat
        lateinit var view: TextView
        lateinit var params: WindowManager.LayoutParams
        var nextAt = 0L
        var count = 0
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                tap(this@Bubble)
                count++
                if (target.repeat > 0 && count >= target.repeat) {
                    bubbles.forEach { it.view.alpha = if (it.finished()) 0.3f else 1f }
                    if (bubbles.all { it.finished() }) stopRunning()
                    return
                }
                nextAt += target.intervalMs
                val now = SystemClock.uptimeMillis()
                if (nextAt < now) nextAt = now // fell behind; don't burst
                handler.postAtTime(this, nextAt)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val bubbles = mutableListOf<Bubble>()
    private var panel: View? = null
    private var playBtn: Button? = null
    private var editor: View? = null
    private var running = false
    private val timerStop = Runnable { stopRunning() }
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopRunning() }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }

    // ---- overlay lifecycle -------------------------------------------------------------------

    fun showOverlay() {
        if (panel != null) return
        TargetStore.load(this).forEach { addBubble(it) }
        addPanel()
    }

    private fun hideOverlay() {
        stopRunning()
        closeEditor()
        bubbles.forEach { runCatching { wm.removeView(it.view) } }
        bubbles.clear()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun overlayParams(touchable: Boolean, focusable: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // ---- control panel -----------------------------------------------------------------------

    private fun addPanel() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 30, 30, 30)); cornerRadius = dp(24).toFloat()
            }
        }
        val params = overlayParams(touchable = true).apply { x = dp(8); y = dp(120) }

        val handle = TextView(this).apply {
            text = "⠿"; setTextColor(Color.WHITE); textSize = 22f
            setPadding(dp(10), 0, dp(10), 0); gravity = Gravity.CENTER
        }
        attachDrag(handle, params, bar, onClick = null, onDrop = null)

        fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; minWidth = 0; minimumWidth = dp(48); setOnClickListener { onClick() }
        }
        val play = barButton("▶") { toggleRunning() }
        playBtn = play
        bar.addView(handle)
        bar.addView(play)
        bar.addView(barButton("+") { addNewBubble() })
        bar.addView(barButton("✕") { hideOverlay() })

        panel = bar
        wm.addView(bar, params)
    }

    // ---- bubbles -----------------------------------------------------------------------------

    private fun addNewBubble() {
        if (running) return
        val m = resources.displayMetrics
        val t = Target(m.widthPixels / 2 - dp(28), m.heightPixels / 2 - dp(28))
        addBubble(t)
        persist()
    }

    private fun addBubble(t: Target) {
        val b = Bubble(t)
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            width = dp(56); height = dp(56)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(150, 33, 150, 243))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val params = overlayParams(touchable = true).apply { x = t.x; y = t.y }
        b.view = tv; b.params = params
        bubbles.add(b)
        refreshLabels()
        attachDrag(tv, params, tv,
            onClick = { openEditor(b) },
            onDrop = { t.x = params.x; t.y = params.y; persist() })
        wm.addView(tv, params)
    }

    private fun refreshLabels() {
        bubbles.forEachIndexed { i, b -> b.view.text = "${i + 1}" }
    }

    private fun persist() = TargetStore.save(this, bubbles.map { it.target })

    private fun attachDrag(
        touchSource: View, params: WindowManager.LayoutParams, windowView: View,
        onClick: (() -> Unit)?, onDrop: (() -> Unit)?
    ) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = dp(8)
        touchSource.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        wm.updateViewLayout(windowView, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) onDrop?.invoke() else onClick?.invoke()
            }
            true
        }
    }

    // ---- editor ------------------------------------------------------------------------------

    private fun openEditor(b: Bubble) {
        if (running) return
        closeEditor()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
        }
        box.addView(TextView(this).apply {
            text = "Button ${bubbles.indexOf(b) + 1}: tap every (ms)"
            setTextColor(Color.BLACK); textSize = 16f
        })
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(b.target.intervalMs.toString()); setSelectAllOnFocus(true)
            setTextColor(Color.BLACK)
        }
        box.addView(input)
        box.addView(TextView(this).apply {
            text = "Number of taps (0 = unlimited)"; setTextColor(Color.BLACK); textSize = 16f
        })
        val repeatInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(b.target.repeat.toString()); setTextColor(Color.BLACK)
        }
        box.addView(repeatInput)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val v = input.text.toString().toLongOrNull()
                if (v != null) {
                    b.target.intervalMs = v.coerceAtLeast(MIN_INTERVAL_MS)
                }
                b.target.repeat = repeatInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                persist()
                closeEditor()
            }
        })
        row.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener {
                runCatching { wm.removeView(b.view) }
                bubbles.remove(b); refreshLabels(); persist(); closeEditor()
            }
        })
        row.addView(Button(this).apply { text = "Cancel"; setOnClickListener { closeEditor() } })
        box.addView(row)

        val params = overlayParams(touchable = true, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        editor = box
        wm.addView(box, params)
        input.requestFocus()
    }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
    }

    // ---- running -----------------------------------------------------------------------------

    private fun toggleRunning() = if (running) stopRunning() else startRunning()

    private fun startRunning() {
        if (bubbles.isEmpty()) return
        closeEditor()
        running = true
        playBtn?.text = "■"
        val now = SystemClock.uptimeMillis()
        val minutes = Prefs.timerMinutes(this)
        if (minutes > 0) handler.postDelayed(timerStop, minutes * 60_000L)
        bubbles.forEach { b ->
            b.count = 0
            b.view.alpha = 1f
            b.params.flags = b.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(b.view, b.params)
            b.nextAt = now + b.target.intervalMs
            handler.postAtTime(b.tick, b.nextAt)
        }
    }

    private fun stopRunning() {
        running = false
        handler.removeCallbacks(timerStop)
        playBtn?.text = "▶"
        bubbles.forEach { b ->
            b.view.alpha = 1f
            handler.removeCallbacks(b.tick)
            b.params.flags = b.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(b.view, b.params) }
        }
    }

    private fun tap(b: Bubble) {
        val loc = IntArray(2)
        b.view.getLocationOnScreen(loc)
        val path = Path().apply {
            moveTo(loc[0] + b.view.width / 2f, loc[1] + b.view.height / 2f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
