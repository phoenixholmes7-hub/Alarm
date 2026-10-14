package com.relentless.alarm

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import kotlin.math.ceil

object Colors {
    const val BG = 0xFF101114.toInt()
    const val CARD = 0xFF1E2027.toInt()
    const val CARD_HI = 0xFF2A2D36.toInt()
    const val ACCENT = 0xFFFF4D4D.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xFF9AA0AA.toInt()
    const val OK = 0xFF3DDC84.toInt()
    const val WARN = 0xFFFFB020.toInt()
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
        background = rounded(color, 14)
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
