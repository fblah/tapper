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
    private var playBtn: TextView? = null
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

    private fun iconButton(glyph: String, desc: String, bg: Int, fg: Int = Ui.TEXT, onClick: () -> Unit) =
        TextView(this).apply {
            text = glyph; textSize = 18f; setTextColor(fg); gravity = Gravity.CENTER
            contentDescription = desc
            background = rippled(shape(bg, 0, oval = true))
            setOnClickListener { onClick() }
        }

    private fun addPanel() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(8), dp(6))
            background = shape(Color.argb(235, 26, 29, 36), 28, Color.argb(60, 255, 255, 255))
        }
        val params = overlayParams(touchable = true).apply { x = dp(8); y = dp(120) }

        // Drag handle: a slim grip.
        val handle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        handle.addView(View(this).apply { background = shape(Ui.MUTED, 2) },
            LinearLayout.LayoutParams(dp(4), dp(22)))
        attachDrag(handle, params, bar, onClick = null, onDrop = null)
        bar.addView(handle, LinearLayout.LayoutParams(dp(22), dp(44)))

        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        playBtn = iconButton("▶\uFE0E", "Start", Ui.ACCENT, Color.WHITE) { toggleRunning() }
        add(playBtn!!)
        add(iconButton("+", "Add tap button", Ui.SURFACE2) { addNewBubble(swipe = false) })
        add(iconButton("⇄", "Add swipe button", Ui.SURFACE2) { addNewBubble(swipe = true) })
        add(iconButton("✕", "Hide controls", Ui.SURFACE2, Ui.MUTED) { hideOverlay() })

        panel = bar
        wm.addView(bar, params)
    }

    private fun updatePlayButton() {
        playBtn?.apply {
            text = if (running) "■" else "▶\uFE0E"
            contentDescription = if (running) "Stop" else "Start"
            background = rippled(shape(if (running) Ui.DANGER else Ui.ACCENT, 0, oval = true))
        }
    }

    // ---- bubbles -----------------------------------------------------------------------------

    private fun addNewBubble(swipe: Boolean) {
        if (running) return
        val m = resources.displayMetrics
        val t = Target(m.widthPixels / 2 - dp(26), m.heightPixels / 2 - dp(26), swipe = swipe)
        if (swipe) { t.x2 = t.x; t.y2 = t.y - dp(200) }
        addBubble(t)
        persist()
    }

    private fun circle(color: Int) = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        width = dp(52); height = dp(52)
        background = shape(color, 0, Color.WHITE, oval = true).apply { setStroke(dp(2), Color.argb(230, 255, 255, 255)) }
    }

    private fun addBubble(t: Target) {
        val b = Bubble(t)
        val tv = circle(Color.argb(170, 139, 124, 255))
        val params = overlayParams(touchable = true).apply { x = t.x; y = t.y }
        b.view = tv; b.params = params
        bubbles.add(b)
        refreshLabels()
        attachDrag(tv, params, tv,
            onClick = { openEditor(b) },
            onDrop = { t.x = params.x; t.y = params.y; persist() })
        wm.addView(tv, params)
        if (t.swipe) {
            val ev = circle(Color.argb(170, 34, 211, 238))
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

    private fun fieldBlock(box: LinearLayout, title: String, help: String, field: EditText) {
        box.addView(label(title, 13f, Ui.TEXT, bold = true), lp(top = 14))
        box.addView(field, lp(top = 6))
        box.addView(label(help, 11f, Ui.MUTED), lp(top = 4))
    }

    private fun openEditor(b: Bubble) {
        if (running) return
        closeEditor()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = shape(Ui.SURFACE, 24, Color.argb(50, 255, 255, 255))
        }
        val n = bubbles.indexOf(b) + 1
        box.addView(label(if (b.target.swipe) "Swipe $n" else "Tap $n", 18f, Ui.TEXT, bold = true))

        val input = uiField(b.target.intervalMs.toString()).apply { setSelectAllOnFocus(true) }
        fieldBlock(box, "Interval (ms)", "Time between taps. In sequence mode: wait after this button.", input)
        val repeatInput = uiField(b.target.repeat.toString())
        fieldBlock(box, "Number of taps", "0 = unlimited. In sequence mode: taps in a row.", repeatInput)
        var swipeInput: EditText? = null
        if (b.target.swipe) {
            swipeInput = uiField(b.target.swipeMs.toString())
            fieldBlock(box, "Swipe duration (ms)", "Keep below the interval. Drag the teal marker to set the end.", swipeInput)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(uiButton("Delete", Ui.Kind.DANGER) {
            b.views().forEach { runCatching { wm.removeView(it) } }
            bubbles.remove(b); refreshLabels(); persist(); closeEditor()
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(uiButton("Cancel", Ui.Kind.GHOST) { closeEditor() })
        row.addView(uiButton("Save") {
            input.text.toString().toLongOrNull()?.let { b.target.intervalMs = it.coerceAtLeast(MIN_INTERVAL_MS) }
            swipeInput?.text?.toString()?.toLongOrNull()?.let { b.target.swipeMs = it.coerceAtLeast(10L) }
            b.target.repeat = repeatInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            persist()
            closeEditor()
        }, lp(top = 0).apply { marginStart = dp(6) })
        box.addView(row, lp(top = 18))

        val params = overlayParams(touchable = true, focusable = true).apply {
            width = dp(300)
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
        updatePlayButton()
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
        updatePlayButton()
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
