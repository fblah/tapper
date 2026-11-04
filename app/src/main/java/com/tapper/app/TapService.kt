package com.tapper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.graphics.Color
import android.service.quicksettings.TileService
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
        fun views() = listOfNotNull(view, endView)
        fun finished() = target.repeat > 0 && count >= target.repeat
        lateinit var view: TextView
        lateinit var params: WindowManager.LayoutParams
        var endView: TextView? = null
        var endParams: WindowManager.LayoutParams? = null
        var nextAt = 0L
        var count = 0
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                tap(this@Bubble)
                count++
                if (target.repeat > 0 && count >= target.repeat) {
                    bubbles.forEach { o -> o.views().forEach { it.alpha = if (o.finished()) 0.3f else 1f } }
                    if (bubbles.all { it.finished() }) stopRunning()
                    return
                }
                nextAt += jitteredInterval(target.intervalMs)
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
    val isRunning get() = running
    private val timerStop = Runnable { stopRunning() }
    private var sequenceMode = false
    private var maxCycles = 0
    private var jitterPct = 0
    private var jitterPx = 0
    private var seqIndex = 0
    private var seqRep = 0
    private var seqCycle = 0
    /** Sequence mode: tap button [seqIndex] (its repeat count in a row), wait its interval, move on. */
    private val seqTick = object : Runnable {
        override fun run() {
            if (!running || bubbles.isEmpty()) return
            val b = bubbles[seqIndex]
            tap(b)
            seqRep++
            if (seqRep >= maxOf(1, b.target.repeat)) {
                seqRep = 0
                seqIndex++
                if (seqIndex >= bubbles.size) {
                    seqIndex = 0
                    seqCycle++
                    if (maxCycles > 0 && seqCycle >= maxCycles) { stopRunning(); return }
                }
            }
            handler.postDelayed(this, jitteredInterval(b.target.intervalMs))
        }
    }
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

    /** Re-read the saved layout (after a profile is loaded or imported). */
    fun reload() {
        if (panel == null) return
        hideOverlay()
        showOverlay()
    }

    private fun hideOverlay() {
        stopRunning()
        closeEditor()
        bubbles.forEach { b -> b.views().forEach { runCatching { wm.removeView(it) } } }
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
        bar.addView(barButton("+") { addNewBubble(swipe = false) })
        bar.addView(barButton("⇄") { addNewBubble(swipe = true) })
        bar.addView(barButton("✕") { hideOverlay() })

        panel = bar
        wm.addView(bar, params)
    }

    // ---- bubbles -----------------------------------------------------------------------------

    private fun addNewBubble(swipe: Boolean) {
        if (running) return
        val m = resources.displayMetrics
        val t = Target(m.widthPixels / 2 - dp(28), m.heightPixels / 2 - dp(28), swipe = swipe)
        if (swipe) { t.x2 = t.x; t.y2 = t.y - dp(200) }
        addBubble(t)
        persist()
    }

    private fun circle(color: Int) = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        width = dp(56); height = dp(56)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun addBubble(t: Target) {
        val b = Bubble(t)
        val tv = circle(Color.argb(150, 33, 150, 243))
        val params = overlayParams(touchable = true).apply { x = t.x; y = t.y }
        b.view = tv; b.params = params
        bubbles.add(b)
        refreshLabels()
        attachDrag(tv, params, tv,
            onClick = { openEditor(b) },
            onDrop = { t.x = params.x; t.y = params.y; persist() })
        wm.addView(tv, params)
        if (t.swipe) {
            val ev = circle(Color.argb(150, 255, 152, 0))
            val ep = overlayParams(touchable = true).apply { x = t.x2; y = t.y2 }
            b.endView = ev; b.endParams = ep
            refreshLabels()
            attachDrag(ev, ep, ev,
                onClick = { openEditor(b) },
                onDrop = { t.x2 = ep.x; t.y2 = ep.y; persist() })
            wm.addView(ev, ep)
        }
    }

    private fun refreshLabels() {
        bubbles.forEachIndexed { i, b ->
            b.view.text = if (b.target.swipe) "${i + 1}⇄" else "${i + 1}"
            b.endView?.text = "→${i + 1}"
        }
    }

    private fun setTouchable(b: Bubble, touchable: Boolean) {
        listOfNotNull(b.view to b.params, b.endView?.let { it to b.endParams!! }).forEach { (v, p) ->
            p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(v, p) }
        }
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
            text = "Button ${bubbles.indexOf(b) + 1}: tap every (ms; in sequence mode: wait after)"
            setTextColor(Color.BLACK); textSize = 16f
        })
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(b.target.intervalMs.toString()); setSelectAllOnFocus(true)
            setTextColor(Color.BLACK)
        }
        box.addView(input)
        box.addView(TextView(this).apply {
            text = "Number of taps (0 = unlimited; in sequence mode: taps in a row)"; setTextColor(Color.BLACK); textSize = 16f
        })
        val repeatInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(b.target.repeat.toString()); setTextColor(Color.BLACK)
        }
        box.addView(repeatInput)
        var swipeInput: EditText? = null
        if (b.target.swipe) {
            box.addView(TextView(this).apply {
                text = "Swipe duration (ms, keep below the interval)"; setTextColor(Color.BLACK); textSize = 16f
            })
            swipeInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(b.target.swipeMs.toString()); setTextColor(Color.BLACK)
            }
            box.addView(swipeInput)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val v = input.text.toString().toLongOrNull()
                if (v != null) {
                    b.target.intervalMs = v.coerceAtLeast(MIN_INTERVAL_MS)
                }
                swipeInput?.text?.toString()?.toLongOrNull()?.let { b.target.swipeMs = it.coerceAtLeast(10L) }
                b.target.repeat = repeatInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                persist()
                closeEditor()
            }
        })
        row.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener {
                b.views().forEach { runCatching { wm.removeView(it) } }
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

    fun toggleRunning() = if (running) stopRunning() else startRunning()

    private fun startRunning() {
        if (bubbles.isEmpty()) return
        closeEditor()
        running = true
        playBtn?.text = "■"
        refreshTile()
        val now = SystemClock.uptimeMillis()
        val minutes = Prefs.timerMinutes(this)
        sequenceMode = Prefs.sequence(this)
        maxCycles = Prefs.cycles(this)
        jitterPct = Prefs.jitterPct(this)
        jitterPx = Prefs.jitterPx(this)
        seqIndex = 0; seqRep = 0; seqCycle = 0
        if (minutes > 0) handler.postDelayed(timerStop, minutes * 60_000L)
        bubbles.forEach { b ->
            b.count = 0
            b.views().forEach { it.alpha = 1f }
            setTouchable(b, false)
            if (!sequenceMode) {
                b.nextAt = now + b.target.intervalMs
                handler.postAtTime(b.tick, b.nextAt)
            }
        }
        if (sequenceMode) handler.postDelayed(seqTick, 500)
    }

    private fun stopRunning() {
        running = false
        refreshTile()
        handler.removeCallbacks(timerStop)
        playBtn?.text = "▶"
        bubbles.forEach { b ->
            b.views().forEach { it.alpha = 1f }
            handler.removeCallbacks(b.tick)
            handler.removeCallbacks(seqTick)
            setTouchable(b, true)
        }
    }

    private fun refreshTile() = runCatching {
        TileService.requestListeningState(this, ComponentName(this, TapTile::class.java))
    }

    private fun jitteredInterval(ms: Long): Long {
        if (jitterPct == 0) return ms
        val spread = ms * jitterPct / 100.0
        return (ms + (Math.random() * 2 - 1) * spread).toLong().coerceAtLeast(MIN_INTERVAL_MS)
    }

    private fun jitterPos() = if (jitterPx == 0) 0f else ((Math.random() * 2 - 1) * jitterPx).toFloat()

    private fun tap(b: Bubble) {
        val loc = IntArray(2)
        b.view.getLocationOnScreen(loc)
        val path = Path().apply {
            moveTo(loc[0] + b.view.width / 2f + jitterPos(), loc[1] + b.view.height / 2f + jitterPos())
        }
        var duration = TAP_DURATION_MS
        b.endView?.let { ev ->
            val end = IntArray(2)
            ev.getLocationOnScreen(end)
            path.lineTo(end[0] + ev.width / 2f + jitterPos(), end[1] + ev.height / 2f + jitterPos())
            duration = b.target.swipeMs
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
