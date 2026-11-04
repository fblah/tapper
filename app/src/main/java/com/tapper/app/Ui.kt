package com.tapper.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
