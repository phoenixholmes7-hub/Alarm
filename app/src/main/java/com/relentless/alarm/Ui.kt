package com.relentless.alarm

import android.content.Context
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.ImageView
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import kotlin.math.ceil

object Colors {
    const val BG = 0xFF0E0F13.toInt()
    const val CARD = 0xFF181A20.toInt()
    const val CARD_HI = 0xFF23262E.toInt()
    const val LINE = 0xFF2C2F38.toInt()
    const val ACCENT = 0xFFFF6B4A.toInt()
    const val NAVY = 0xFF2A3170.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xFF8B919C.toInt()
    const val DIM = 0xFF3A3D46.toInt()
    const val OK = 0xFF34D399.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val FLASH = 0xFF8B0000.toInt()
}

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 16) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

fun Context.llp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, side: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = dp(top)
        leftMargin = dp(side)
        rightMargin = dp(side)
    }

fun Context.label(
    s: CharSequence,
    size: Float = 16f,
    color: Int = Colors.TEXT,
    bold: Boolean = false,
    center: Boolean = false,
) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    if (center) gravity = Gravity.CENTER
}

fun Context.button(s: String, color: Int = Colors.ACCENT, size: Float = 17f, onClick: () -> Unit) =
    Button(this).apply {
        text = s
        isAllCaps = false
        textSize = size
        setTextColor(Colors.TEXT)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, 14), null)
        stateListAnimator = null
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

fun Context.vertical(padDp: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
}

fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(): LinearLayout = vertical(16).apply { background = rounded(Colors.CARD) }

fun Context.spacer(h: Int) = View(this).apply { layoutParams = llp(MATCH, dp(h)) }

fun Context.fmtTime(hour: Int, minute: Int): String {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return android.text.format.DateFormat.getTimeFormat(this).format(c.time)
}

fun untilText(ms: Long): String {
    val totalMin = ceil(ms / 60000.0).toLong().coerceAtLeast(1)
    val d = totalMin / (60 * 24)
    val h = (totalMin / 60) % 24
    val m = totalMin % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0 || d > 0) append("${h}h ")
        append("${m}m")
    }
}

/** Card-style background that ripples when tapped. */
fun Context.pressable(color: Int = Colors.CARD, radiusDp: Int = 16) =
    RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), rounded(color, radiusDp), null)

fun Context.holmesFont(): Typeface = resources.getFont(R.font.holmes)

fun Context.holmesLightFont(): Typeface = resources.getFont(R.font.holmes_light)

/** "Wake Up Holmes" — plain "Wake Up", then "Holmes" in the Victorian serif, in the accent colour. */
fun Context.wordmark(size: Float = 26f): TextView {
    val sb = SpannableStringBuilder("Wake Up ")
    val start = sb.length
    sb.append("Holmes")
    sb.setSpan(FontSpan(holmesFont()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.setSpan(ForegroundColorSpan(Colors.ACCENT), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.setSpan(RelativeSizeSpan(1.18f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return label(sb, size, bold = true)
}

fun Context.logo(sizeDp: Int) = ImageView(this).apply {
    setImageResource(R.drawable.ic_logo)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** "7:00 AM" with a smaller "AM". */
fun Context.bigTime(hour: Int, minute: Int): CharSequence {
    val t = fmtTime(hour, minute)
    val sp = t.indexOf(' ')
    if (sp < 0) return t
    return SpannableStringBuilder(t).apply {
        setSpan(RelativeSizeSpan(0.42f), sp, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Header with a back arrow and a title, for secondary screens. */
fun android.app.Activity.titleBar(title: String): LinearLayout = horizontal().apply {
    addView(label("‹", 34f, Colors.TEXT).apply {
        setPadding(0, 0, dp(16), dp(4))
        setOnClickListener { finish() }
    })
    addView(label(title, 22f, bold = true), llp(0, WRAP, 1f))
}

/** Applies a Typeface to a span of text (TypefaceSpan(Typeface) needs Android 9+). */
class FontSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = face
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = face
    }
}

fun Context.pill(s: String, selected: Boolean = false, onClick: () -> Unit) = label(s, 14f, if (selected) Colors.TEXT else Colors.SUB).apply {
    background = pressable(if (selected) Colors.ACCENT else Colors.CARD_HI, 20)
    setPadding(dp(14), dp(8), dp(14), dp(8))
    setOnClickListener { onClick() }
}
