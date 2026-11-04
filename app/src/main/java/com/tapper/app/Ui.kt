package com.tapper.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Shared look for the main screen and the floating overlay: dark, soft cards, violet accent. */
object Ui {
    val BG = Color.parseColor("#0F1115")
    val SURFACE = Color.parseColor("#1A1D24")
    val SURFACE2 = Color.parseColor("#262A35")
    val ACCENT = Color.parseColor("#8B7CFF")
    val TEAL = Color.parseColor("#22D3EE")
    val TEXT = Color.parseColor("#F2F4F8")
    val MUTED = Color.parseColor("#9AA3B2")
    val DANGER = Color.parseColor("#FF5C6C")
    val OK = Color.parseColor("#34D399")
    val WARN = Color.parseColor("#FBBF24")
    val TEAL_DARK = Color.parseColor("#0891B2")
    val FX_RUN = Color.parseColor("#C8C0FF")

    enum class Kind { PRIMARY, SECONDARY, DANGER, GHOST }
}

fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

fun Context.shape(color: Int, radiusDp: Int, strokeColor: Int? = null, oval: Boolean = false) =
    GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = px(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(px(1), strokeColor)
    }

fun Context.rippled(content: GradientDrawable) =
    RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, null)

fun Context.label(text: String, sp: Float = 15f, color: Int = Ui.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text; textSize = sp; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

fun Context.uiButton(text: String, kind: Ui.Kind = Ui.Kind.PRIMARY, onClick: () -> Unit) =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        textSize = 14f
        setPadding(px(16), px(10), px(16), px(10))
        val (bg, fg, stroke) = when (kind) {
            Ui.Kind.PRIMARY -> Triple(Ui.ACCENT, Color.WHITE, null)
            Ui.Kind.SECONDARY -> Triple(Ui.SURFACE2, Ui.TEXT, null)
            Ui.Kind.DANGER -> Triple(Color.TRANSPARENT, Ui.DANGER, Ui.DANGER)
            Ui.Kind.GHOST -> Triple(Color.TRANSPARENT, Ui.MUTED, null)
        }
        setTextColor(fg)
        background = rippled(shape(bg, 12, stroke))
        setOnClickListener { onClick() }
    }

fun Context.uiField(initial: String, numeric: Boolean = true, hint: String? = null) =
    EditText(this).apply {
        setText(initial)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        this.hint = hint
        setSingleLine()
        textSize = 15f
        setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        gravity = if (numeric) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(px(12), px(10), px(12), px(10))
        background = shape(Ui.SURFACE2, 10)
    }

fun Context.uiCard() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(px(16), px(16), px(16), px(16))
    background = shape(Ui.SURFACE, 20)
}

/** Title (+ optional description) on the left, [control] on the right. */
fun Context.settingRow(title: String, desc: String?, control: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(8), 0, px(8))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, Ui.TEXT))
            if (desc != null) addView(label(desc, 12f, Ui.MUTED))
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(control)
    }

fun Context.lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
               top: Int = 0, end: Int = 0) =
    LinearLayout.LayoutParams(w, h).apply { topMargin = px(top); marginEnd = px(end) }

/** Crisp vector icons drawn with Canvas (no font/emoji dependence). Put a circular background on the view. */
class GlyphView(ctx: Context, var kind: Kind, var tint: Int = Color.WHITE) : View(ctx) {
    enum class Kind { PLAY, STOP, REC, REPLAY, PLUS, SWAP, CHEV_LEFT, CHEV_RIGHT, CLOSE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun update(kind: Kind, tint: Int = this.tint) { this.kind = kind; this.tint = tint; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val u = minOf(width, height) / 2f
        val r = u * 0.42f
        fill.color = tint; line.color = tint; line.strokeWidth = u * 0.15f
        path.reset()
        when (kind) {
            Kind.PLAY -> {
                val l = cx - r * 0.45f
                path.moveTo(l, cy - r); path.lineTo(l, cy + r); path.lineTo(cx + r * 1.05f, cy); path.close()
                c.drawPath(path, fill)
            }
            Kind.STOP -> c.drawRoundRect(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, r * 0.2f, r * 0.2f, fill)
            Kind.REC -> c.drawCircle(cx, cy, r * 0.85f, fill)
            Kind.REPLAY -> {
                val big = r * 0.95f
                c.drawArc(RectF(cx - big, cy - big, cx + big, cy + big), -50f, 270f, false, line)
                val th = Math.toRadians(220.0)
                val rx = Math.cos(th).toFloat(); val ry = Math.sin(th).toFloat()
                val ex = cx + big * rx; val ey = cy + big * ry
                val tx = -ry; val ty = rx // clockwise tangent
                val h = r * 0.55f
                path.moveTo(ex + tx * r * 0.7f, ey + ty * r * 0.7f)
                path.lineTo(ex + rx * h, ey + ry * h)
                path.lineTo(ex - rx * h, ey - ry * h)
                path.close()
                c.drawPath(path, fill)
                path.reset()
                val l = cx - r * 0.2f
                path.moveTo(l, cy - r * 0.38f); path.lineTo(l, cy + r * 0.38f); path.lineTo(l + r * 0.6f, cy); path.close()
                c.drawPath(path, fill)
            }
            Kind.PLUS -> {
                c.drawLine(cx - r * 0.9f, cy, cx + r * 0.9f, cy, line)
                c.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.9f, line)
            }
            Kind.SWAP -> {
                val y1 = cy - r * 0.45f; val y2 = cy + r * 0.45f
                c.drawLine(cx - r, y1, cx + r, y1, line)
                path.moveTo(cx + r * 0.45f, y1 - r * 0.4f); path.lineTo(cx + r, y1); path.lineTo(cx + r * 0.45f, y1 + r * 0.4f)
                c.drawLine(cx + r, y2, cx - r, y2, line)
                path.moveTo(cx - r * 0.45f, y2 - r * 0.4f); path.lineTo(cx - r, y2); path.lineTo(cx - r * 0.45f, y2 + r * 0.4f)
                c.drawPath(path, line)
            }
            Kind.CHEV_LEFT -> {
                path.moveTo(cx + r * 0.35f, cy - r * 0.85f); path.lineTo(cx - r * 0.4f, cy); path.lineTo(cx + r * 0.35f, cy + r * 0.85f)
                c.drawPath(path, line)
            }
            Kind.CHEV_RIGHT -> {
                path.moveTo(cx - r * 0.35f, cy - r * 0.85f); path.lineTo(cx + r * 0.4f, cy); path.lineTo(cx - r * 0.35f, cy + r * 0.85f)
                c.drawPath(path, line)
            }
            Kind.CLOSE -> {
                c.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, line)
                c.drawLine(cx + r * 0.7f, cy - r * 0.7f, cx - r * 0.7f, cy + r * 0.7f, line)
            }
        }
    }
}

/**
 * Full-screen, touch-transparent layer that draws short-lived tap rings, swipe trails and dots.
 * Positions are raw screen coordinates; the view converts them with its own on-screen offset.
 */
class FxView(ctx: Context) : View(ctx) {
    private class Item(
        val kind: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val start: Long, val dur: Long, val moveMs: Long, val color: Int
    )

    private val items = ArrayList<Item>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private val d = resources.displayMetrics.density

    fun tap(x: Float, y: Float, color: Int) =
        add(Item(0, x, y, x, y, SystemClock.uptimeMillis(), 520, 0, color))

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long, color: Int) =
        add(Item(1, x1, y1, x2, y2, SystemClock.uptimeMillis(), moveMs + 350, moveMs.coerceAtLeast(1), color))

    fun dot(x: Float, y: Float, color: Int) =
        add(Item(2, x, y, x, y, SystemClock.uptimeMillis(), 260, 0, color))

    private fun add(i: Item) {
        if (items.size > 40) items.removeAt(0)
        items.add(i)
        postInvalidateOnAnimation()
    }

    private fun alpha(color: Int, a: Float) =
        Color.argb((255 * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat(); val oy = loc[1].toFloat()
        val now = SystemClock.uptimeMillis()
        val iter = items.iterator()
        while (iter.hasNext()) {
            val i = iter.next()
            val age = now - i.start
            if (age >= i.dur) { iter.remove(); continue }
            val p = age.toFloat() / i.dur
            val x1 = i.x1 - ox; val y1 = i.y1 - oy
            when (i.kind) {
                0 -> {
                    val r = d * 10 + d * 36 * (1 - (1 - p) * (1 - p))
                    val a = 1 - p
                    paint.style = Paint.Style.FILL; paint.color = alpha(i.color, 0.28f * a)
                    c.drawCircle(x1, y1, r, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = d * 3; paint.color = alpha(i.color, 0.95f * a)
                    c.drawCircle(x1, y1, r, paint)
                }
                1 -> {
                    val pm = minOf(1f, age.toFloat() / i.moveMs)
                    val cx = x1 + (i.x2 - ox - x1) * pm; val cy = y1 + (i.y2 - oy - y1) * pm
                    val a = if (age <= i.moveMs) 1f else 1f - (age - i.moveMs) / 350f
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = d * 4; paint.strokeCap = Paint.Cap.ROUND
                    paint.color = alpha(i.color, 0.7f * a)
                    c.drawLine(x1, y1, cx, cy, paint)
                    paint.style = Paint.Style.FILL; paint.color = alpha(i.color, 0.9f * a)
                    c.drawCircle(cx, cy, d * 12, paint)
                }
                else -> {
                    paint.style = Paint.Style.FILL; paint.color = alpha(i.color, 0.8f * (1 - p))
                    c.drawCircle(x1, y1, d * 7 * (1 - p * 0.5f), paint)
                }
            }
        }
        if (items.isNotEmpty()) postInvalidateOnAnimation()
    }
}
