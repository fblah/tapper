package com.tapper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating tap buttons (accessibility overlay windows) that inject taps with dispatchGesture, plus a
 * recorder/replayer for your own touches. While anything runs, button overlays are not touchable so
 * injected taps pass through them.
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

    // overlay windows
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var fx: FxView? = null
    private var editor: View? = null
    private var capture: View? = null
    private var captureParams: WindowManager.LayoutParams? = null

    // bar widgets
    private var group: LinearLayout? = null
    private var collapseBtn: GlyphView? = null
    private var runBtn: GlyphView? = null
    private var recBtn: GlyphView? = null
    private var replayBtn: GlyphView? = null
    private var stopBtn: GlyphView? = null
    private var collapsed = false

    // modes
    private var running = false
    private var recording = false
    private var playing = false
    val isRunning get() = running || recording || playing
    private fun anyActive() = running || recording || playing

    // run settings (read when a run starts)
    private val timerStop = Runnable { stopAll() }
    private var sequenceMode = false
    private var maxCycles = 0
    private var jitterPct = 0
    private var jitterPx = 0
    private var seqIndex = 0
    private var seqRep = 0
    private var seqCycle = 0

    // recording / replay
    private var macro = mutableListOf<Stroke>()
    private var curStroke: Stroke? = null
    private var curDown = 0L
    private var recFirstDown = -1L
    private var lastX = 0f
    private var lastY = 0f
    private val playToken = Any()
    private var gapMs = 0L

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

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

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAll() }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- overlay lifecycle -------------------------------------------------------------------

    fun showOverlay() {
        if (panel != null) return
        macro = MacroStore.load(this)
        TargetStore.load(this).forEach { addBubble(it) }
        addFx()
        addPanel()
    }

    /** Re-read the saved layout (after a profile is loaded or imported). */
    fun reload() {
        if (panel == null) return
        hideOverlay()
        showOverlay()
    }

    private fun hideOverlay() {
        stopAll()
        closeEditor()
        bubbles.forEach { b -> b.views().forEach { runCatching { wm.removeView(it) } } }
        bubbles.clear()
        fx?.let { runCatching { wm.removeView(it) } }
        fx = null
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

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun addFx() {
        val v = FxView(this)
        fx = v
        wm.addView(v, fullScreenParams(touchable = false))
    }

    // ---- control panel -----------------------------------------------------------------------

    private fun iconButton(
        kind: GlyphView.Kind, desc: String, bg: Int, tint: Int = Color.WHITE,
        onLong: (() -> Unit)? = null, onClick: () -> Unit
    ) = GlyphView(this, kind, tint).apply {
        contentDescription = desc
        background = rippled(shape(bg, 0, oval = true))
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLong(); true
        }
    }

    private fun LinearLayout.addBtn(v: View) =
        addView(v, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })

    private fun addPanel() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(8), dp(5))
            background = shape(Color.argb(235, 26, 29, 36), 28, Color.argb(60, 255, 255, 255))
        }
        val params = overlayParams(touchable = true).apply { x = dp(8); y = dp(120) }
        panelParams = params

        // Drag handle: a slim grip.
        val handle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        handle.addView(View(this).apply { background = shape(Ui.MUTED, 2) }, LinearLayout.LayoutParams(dp(4), dp(22)))
        attachDrag(handle, params, bar, onClick = null, onDrop = null)
        bar.addView(handle, LinearLayout.LayoutParams(dp(22), dp(40)))

        val chevron = iconButton(GlyphView.Kind.CHEV_LEFT, "Collapse", Ui.SURFACE2, Ui.MUTED) { setCollapsed(!collapsed) }
        collapseBtn = chevron
        bar.addBtn(chevron)

        val g = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        group = g
        runBtn = iconButton(GlyphView.Kind.PLAY, "Start tap buttons", Ui.ACCENT) {
            if (running) stopRunning() else { stopAll(); startRunning() }
        }
        recBtn = iconButton(GlyphView.Kind.REC, "Record", Ui.SURFACE2, Ui.DANGER) {
            if (recording) stopRecording() else { stopAll(); startRecording() }
        }
        replayBtn = iconButton(GlyphView.Kind.REPLAY, "Replay recording (long press for settings)", Ui.TEAL_DARK,
            onLong = { openMacroEditor() }) {
            if (playing) stopPlayback() else { stopAll(); startPlayback() }
        }
        g.addBtn(runBtn!!)
        g.addBtn(recBtn!!)
        g.addBtn(replayBtn!!)
        g.addBtn(iconButton(GlyphView.Kind.PLUS, "Add tap button", Ui.SURFACE2) { addNewBubble(swipe = false) })
        g.addBtn(iconButton(GlyphView.Kind.SWAP, "Add swipe button", Ui.SURFACE2) { addNewBubble(swipe = true) })
        g.addBtn(iconButton(GlyphView.Kind.CLOSE, "Hide controls", Ui.SURFACE2, Ui.MUTED) { hideOverlay() })
        bar.addView(g)

        // Shown only while collapsed and something is running, so you can always stop.
        val stop = iconButton(GlyphView.Kind.STOP, "Stop", Ui.DANGER) { stopAll() }
        stopBtn = stop
        stop.visibility = View.GONE
        bar.addBtn(stop)

        panel = bar
        wm.addView(bar, params)
        collapsed = Prefs.collapsed(this)
        applyCollapsed()
        updateButtons()
    }

    private fun setCollapsed(c: Boolean) {
        collapsed = c
        Prefs.setCollapsed(this, c)
        applyCollapsed()
        updateButtons()
        // Keep the bar on screen after it grows.
        panel?.post {
            val bar = panel ?: return@post
            val p = panelParams ?: return@post
            val maxX = resources.displayMetrics.widthPixels - bar.width
            if (p.x > maxX) { p.x = maxOf(0, maxX); runCatching { wm.updateViewLayout(bar, p) } }
        }
    }

    private fun applyCollapsed() {
        group?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseBtn?.update(if (collapsed) GlyphView.Kind.CHEV_RIGHT else GlyphView.Kind.CHEV_LEFT)
        collapseBtn?.contentDescription = if (collapsed) "Expand" else "Collapse"
    }

    private fun setBtn(v: GlyphView?, kind: GlyphView.Kind, bg: Int, tint: Int = Color.WHITE) {
        v ?: return
        v.update(kind, tint)
        v.background = rippled(shape(bg, 0, oval = true))
    }

    private fun updateButtons() {
        setBtn(runBtn, if (running) GlyphView.Kind.STOP else GlyphView.Kind.PLAY, if (running) Ui.DANGER else Ui.ACCENT)
        setBtn(recBtn, if (recording) GlyphView.Kind.STOP else GlyphView.Kind.REC,
            if (recording) Ui.DANGER else Ui.SURFACE2, if (recording) Color.WHITE else Ui.DANGER)
        setBtn(replayBtn, if (playing) GlyphView.Kind.STOP else GlyphView.Kind.REPLAY,
            if (playing) Ui.DANGER else Ui.TEAL_DARK)
        replayBtn?.alpha = if (macro.isEmpty() && !playing) 0.4f else 1f
        stopBtn?.visibility = if (collapsed && anyActive()) View.VISIBLE else View.GONE
        refreshTile()
    }

    // ---- bubbles -----------------------------------------------------------------------------

    private fun addNewBubble(swipe: Boolean) {
        if (anyActive()) return
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

    /** While recording/replaying, bubbles fade out and let touches through. */
    private fun setBubblesInactive(on: Boolean) {
        bubbles.forEach { b ->
            setTouchable(b, !on)
            b.views().forEach { it.alpha = if (on) 0.25f else 1f }
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

    // ---- editors -----------------------------------------------------------------------------

    private fun fieldBlock(box: LinearLayout, title: String, help: String, field: EditText) {
        box.addView(label(title, 13f, Ui.TEXT, bold = true), lp(top = 14))
        box.addView(field, lp(top = 6))
        box.addView(label(help, 11f, Ui.MUTED), lp(top = 4))
    }

    private fun editorCard(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = shape(Ui.SURFACE, 24, Color.argb(50, 255, 255, 255))
        }
        box.addView(label(title, 18f, Ui.TEXT, bold = true))
        return box
    }

    /** Button row: [left?] ...spacer... [Cancel] [Save]. Buttons are wrap-content so Save sits right. */
    private fun buttonRow(left: View?, cancel: () -> Unit, save: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (left != null) row.addView(left, wrap())
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(uiButton("Cancel", Ui.Kind.GHOST) { cancel() }, wrap())
        row.addView(uiButton("Save") { save() }, wrap().apply { marginStart = dp(6) })
        return row
    }

    private fun showEditor(box: View, focus: View) {
        val params = overlayParams(touchable = true, focusable = true).apply {
            width = dp(300)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        editor = box
        wm.addView(box, params)
        focus.requestFocus()
    }

    private fun openEditor(b: Bubble) {
        if (anyActive()) return
        closeEditor()
        val n = bubbles.indexOf(b) + 1
        val box = editorCard(if (b.target.swipe) "Swipe $n" else "Tap $n")

        val input = uiField(b.target.intervalMs.toString()).apply { setSelectAllOnFocus(true) }
        fieldBlock(box, "Interval (ms)", "Time between taps. In sequence mode: wait after this button.", input)
        val repeatInput = uiField(b.target.repeat.toString())
        fieldBlock(box, "Number of taps", "0 = unlimited. In sequence mode: taps in a row.", repeatInput)
        var swipeInput: EditText? = null
        if (b.target.swipe) {
            swipeInput = uiField(b.target.swipeMs.toString())
            fieldBlock(box, "Swipe duration (ms)", "Keep below the interval. Drag the teal marker to set the end.", swipeInput)
        }

        val delete = uiButton("Delete", Ui.Kind.DANGER) {
            b.views().forEach { runCatching { wm.removeView(it) } }
            bubbles.remove(b); refreshLabels(); persist(); closeEditor()
        }
        box.addView(buttonRow(delete, cancel = { closeEditor() }, save = {
            input.text.toString().toLongOrNull()?.let { b.target.intervalMs = it.coerceAtLeast(MIN_INTERVAL_MS) }
            swipeInput?.text?.toString()?.toLongOrNull()?.let { b.target.swipeMs = it.coerceAtLeast(10L) }
            b.target.repeat = repeatInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            persist()
            closeEditor()
        }), lp(top = 18))
        showEditor(box, input)
    }

    /** Long-press on replay: how long to keep replaying, and the pause between repeats. */
    private fun openMacroEditor() {
        if (anyActive()) return
        closeEditor()
        val box = editorCard("Replay settings")
        val secs = uiField(Prefs.macroSeconds(this).toString()).apply { setSelectAllOnFocus(true) }
        fieldBlock(box, "Play for (seconds)", "0 = unlimited. The recording repeats until the time is up or you press stop.", secs)
        val gap = uiField(Prefs.macroGapMs(this).toString())
        fieldBlock(box, "Pause between repeats (ms)", "Added after each full run of the recording.", gap)
        box.addView(buttonRow(null, cancel = { closeEditor() }, save = {
            Prefs.setMacroSeconds(this, secs.text.toString().toIntOrNull() ?: 0)
            Prefs.setMacroGapMs(this, gap.text.toString().toIntOrNull() ?: 0)
            closeEditor()
        }), lp(top = 18))
        showEditor(box, secs)
    }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
    }

    // ---- stopping / bookkeeping --------------------------------------------------------------

    fun toggleRunning() = if (anyActive()) stopAll() else startRunning()

    private fun stopAll() {
        if (running) stopRunning()
        if (recording) stopRecording()
        if (playing) stopPlayback()
    }

    private fun refreshTile() = runCatching {
        TileService.requestListeningState(this, ComponentName(this, TapTile::class.java))
    }

    // ---- running tap buttons -----------------------------------------------------------------

    private fun startRunning() {
        if (bubbles.isEmpty()) { toast("Add a tap button first"); return }
        closeEditor()
        running = true
        updateButtons()
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
        if (!running) return
        running = false
        handler.removeCallbacks(timerStop)
        handler.removeCallbacks(seqTick)
        bubbles.forEach { b ->
            b.views().forEach { it.alpha = 1f }
            handler.removeCallbacks(b.tick)
            setTouchable(b, true)
        }
        updateButtons()
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
        val sx = loc[0] + b.view.width / 2f
        val sy = loc[1] + b.view.height / 2f
        val path = Path().apply { moveTo(sx + jitterPos(), sy + jitterPos()) }
        var duration = TAP_DURATION_MS
        b.endView?.let { ev ->
            val end = IntArray(2)
            ev.getLocationOnScreen(end)
            val ex = end[0] + ev.width / 2f
            val ey = end[1] + ev.height / 2f
            path.lineTo(ex + jitterPos(), ey + jitterPos())
            duration = b.target.swipeMs
            fx?.swipe(sx, sy, ex, ey, duration, Ui.FX_RUN)
        }
        fx?.tap(sx, sy, Ui.FX_RUN)
        pulse(b)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Quick grow-and-settle on the button itself so you can see which one fired. */
    private fun pulse(b: Bubble) {
        b.views().forEach { v ->
            v.animate().cancel()
            v.scaleX = 1f; v.scaleY = 1f
            v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(60).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
        }
    }

    // ---- recording ---------------------------------------------------------------------------

    private fun startRecording() {
        closeEditor()
        macro.clear()
        curStroke = null
        recFirstDown = -1
        recording = true
        setBubblesInactive(true)
        jitterPx = 0

        // A transparent full-screen layer with a red frame captures your touches. Each finished touch is
        // saved and then re-played into the app underneath so the app still reacts as normal.
        val v = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.argb(200, 255, 92, 108))
            }
            setOnTouchListener { _, e -> onCaptureTouch(e) }
        }
        val p = fullScreenParams(touchable = true)
        wm.addView(v, p)
        capture = v; captureParams = p
        // Re-add the bar so it sits above the capture layer (the stop tap must never be recorded).
        handler.post { bringPanelToFront() }
        updateButtons()
        toast("Recording – do your taps, then press stop")
    }

    private fun bringPanelToFront() {
        val bar = panel ?: return
        val p = panelParams ?: return
        if (!recording) return
        runCatching { wm.removeView(bar); wm.addView(bar, p) }
    }

    private fun insideBar(x: Float, y: Float): Boolean {
        val bar = panel ?: return false
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        val m = dp(8)
        return x >= loc[0] - m && x <= loc[0] + bar.width + m && y >= loc[1] - m && y <= loc[1] + bar.height + m
    }

    private fun onCaptureTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (insideBar(e.rawX, e.rawY)) return true // never record touches on the controls
                val now = SystemClock.uptimeMillis()
                if (recFirstDown < 0) recFirstDown = now
                curDown = now
                curStroke = Stroke(now - recFirstDown, 0, mutableListOf(floatArrayOf(e.rawX, e.rawY)))
                lastX = e.rawX; lastY = e.rawY
                fx?.tap(e.rawX, e.rawY, Ui.DANGER)
            }
            MotionEvent.ACTION_MOVE -> {
                val s = curStroke ?: return true
                if (hypot(e.rawX - lastX, e.rawY - lastY) >= dp(10) && s.pts.size < 150) {
                    s.pts.add(floatArrayOf(e.rawX, e.rawY))
                    lastX = e.rawX; lastY = e.rawY
                    fx?.dot(e.rawX, e.rawY, Ui.DANGER)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = curStroke ?: return true
                curStroke = null
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (hypot(e.rawX - lastX, e.rawY - lastY) > dp(4)) s.pts.add(floatArrayOf(e.rawX, e.rawY))
                    s.d = (SystemClock.uptimeMillis() - curDown).coerceIn(1L, 60_000L)
                    macro.add(s)
                    inject(s)
                }
            }
        }
        return true
    }

    /** Replay a just-recorded touch into the app under our capture layer. */
    private fun inject(s: Stroke) {
        val cv = capture
        val cp = captureParams
        if (cv != null && cp != null) {
            cp.flags = cp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(cv, cp) }
        }
        val restore = Runnable {
            val c2 = capture
            val p2 = captureParams
            if (recording && c2 != null && p2 != null) {
                p2.flags = p2.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(c2, p2) }
            }
        }
        // Small delay so the window really is touch-transparent before the gesture lands.
        handler.postDelayed({
            val ok = dispatchGesture(buildGesture(s, 0f, 0f), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { handler.postDelayed(restore, 30) }
                override fun onCancelled(gestureDescription: GestureDescription?) { handler.postDelayed(restore, 30) }
            }, handler)
            if (!ok) restore.run()
        }, 60)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        curStroke = null
        capture?.let { runCatching { wm.removeView(it) } }
        capture = null; captureParams = null
        setBubblesInactive(false)
        MacroStore.save(this, macro)
        updateButtons()
        toast(if (macro.isEmpty()) "No taps recorded" else "Recorded ${macro.size} tap${if (macro.size == 1) "" else "s"}")
    }

    // ---- replay ------------------------------------------------------------------------------

    private fun startPlayback() {
        if (macro.isEmpty()) { toast("Record something first"); return }
        closeEditor()
        playing = true
        setBubblesInactive(true)
        jitterPx = Prefs.jitterPx(this)
        gapMs = Prefs.macroGapMs(this).toLong()
        updateButtons()
        val now = SystemClock.uptimeMillis()
        val limit = Prefs.macroSeconds(this) * 1000L
        if (limit > 0) handler.postAtTime(Runnable { stopPlayback() }, playToken, now + limit)
        scheduleLoop(now + 200)
    }

    /** Queue one full run of the recording starting at [loopStart], then the next run after the pause. */
    private fun scheduleLoop(loopStart: Long) {
        val strokes = macro.toList()
        if (strokes.isEmpty()) { stopPlayback(); return }
        for (s in strokes) {
            handler.postAtTime(Runnable { if (playing) playStroke(s) }, playToken, loopStart + s.t)
        }
        val next = loopStart + strokes.maxOf { it.t + it.d } + gapMs
        handler.postAtTime(Runnable { if (playing) scheduleLoop(next) }, playToken, next)
    }

    private fun playStroke(s: Stroke) {
        val first = s.pts.first()
        fx?.tap(first[0], first[1], Ui.TEAL)
        if (s.pts.size > 1) {
            val last = s.pts.last()
            fx?.swipe(first[0], first[1], last[0], last[1], s.d, Ui.TEAL)
        }
        dispatchGesture(buildGesture(s, jitterPos(), jitterPos()), null, null)
    }

    private fun stopPlayback() {
        if (!playing) return
        playing = false
        handler.removeCallbacksAndMessages(playToken)
        setBubblesInactive(false)
        updateButtons()
    }

    private fun buildGesture(s: Stroke, jx: Float, jy: Float): GestureDescription {
        val path = Path()
        s.pts.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p[0] + jx, p[1] + jy) else path.lineTo(p[0] + jx, p[1] + jy)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, s.d.coerceIn(1L, 60_000L)))
            .build()
    }
}
