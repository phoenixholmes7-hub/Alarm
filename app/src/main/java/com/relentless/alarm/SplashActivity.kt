package com.relentless.alarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

/** Startup screen: the logo pops in, then "Wake Up" and a big "Holmes" in Playfair Display. */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical(24).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Colors.BG)
        }
        val mark = logo(132)
        val wake = label("W A K E   U P", 16f, Colors.SUB, bold = true, center = true).apply {
            letterSpacing = 0.25f
        }
        val holmes = label("Holmes", 72f, Colors.ACCENT, center = true).apply {
            typeface = holmesFont()
            includeFontPadding = false
        }
        val rule = View(this).apply { setBackgroundColor(Colors.ACCENT) }
        val tagline = label("Elementary. It's time to get up.", 17f, Colors.SUB, center = true).apply {
            typeface = holmesLightFont()
        }
        root.addView(mark)
        root.addView(wake, llp(top = 36))
        root.addView(holmes, llp(top = 4))
        root.addView(rule, LinearLayout.LayoutParams(dp(56), dp(2)).apply { topMargin = dp(14) })
        root.addView(tagline, llp(top = 14))
        setContentView(root)

        mark.alpha = 0f; mark.scaleX = 0.6f; mark.scaleY = 0.6f
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        listOf(wake, holmes, tagline).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(350L + i * 140L).setDuration(520)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        rule.alpha = 0f
        rule.scaleX = 0f
        rule.animate().alpha(1f).scaleX(1f).setStartDelay(800).setDuration(500).start()

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
