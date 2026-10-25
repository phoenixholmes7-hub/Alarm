package com.relentless.alarm.wear

import android.app.Activity
import android.graphics.Typeface
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/** Full-screen, hard-buzzing alarm on the watch. It only stops when the phone says the mission is done. */
class WatchAlarmActivity : Activity() {
    companion object {
        @Volatile var instance: WatchAlarmActivity? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var clock: TextView
    private lateinit var title: TextView
    private lateinit var info: TextView
    private var vibrator: Vibrator? = null
    private var flash = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        // Round screens: keep everything inside the middle ~70%.
        val pad = (resources.displayMetrics.widthPixels * 0.13).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(30), dp(30)))
        clock = TextView(this).apply {
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        title = TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFFF6B4A.toInt())
            typeface = resources.getFont(R.font.holmes)
            gravity = Gravity.CENTER
        }
        info = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(clock)
        root.addView(title)
        root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        setContentView(root)
        refresh()
        startVibration()
        WatchAlarm.cancelNotification(this)
        handler.post(ticker)
    }

    fun refresh() {
        // The phone re-sends every 20 s, so a changed "siren on watch" setting applies mid-alarm too.
        if (WatchAlarm.sound && vibrator != null && !siren.isPlaying) siren.start(1f, Siren.BEEPER)
        if (!WatchAlarm.sound) siren.stop()
        title.text = if (WatchAlarm.isTest) "Test buzz" else if (WatchAlarm.mode == "check") "Are you awake?" else "Wake up, Holmes!"
        val m = WatchAlarm.missions
        info.text = when {
            WatchAlarm.isTest -> "Your watch is connected ✓"
            WatchAlarm.mode == "check" -> "Confirm on your phone"
            m.isNotBlank() -> "On your phone:\n$m"
            else -> "Turn it off on your phone"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!WatchAlarm.active) {
                finish()
                return
            }
            clock.text = DateFormat.getTimeFormat(this@WatchAlarmActivity).format(Date())
            flash = !flash
            root.setBackgroundColor(
                when {
                    !flash -> 0xFF0E0F13.toInt()
                    WatchAlarm.isTest -> 0xFF2A3170.toInt()
                    else -> if (System.currentTimeMillis() / 300 % 3 == 0L) 0xFFFFFFFF.toInt() else 0xFFCC0000.toInt()
                }
            )
            // Keep the watch's alarm volume pinned to max while ringing.
            if (WatchAlarm.sound) forceVolume()
            handler.postDelayed(this, 300)
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        vibrator = v
        try {
            // Machine-gun bursts then a long hard buzz, all at full strength.
            val timings = longArrayOf(0, 160, 60, 160, 60, 160, 60, 900, 120)
            val effect = if (v?.hasAmplitudeControl() == true) {
                VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0), 1)
            } else {
                VibrationEffect.createWaveform(timings, 1)
            }
            v?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) {
        }
        if (WatchAlarm.sound) {
            forceVolume()
            // Smoke-alarm pitch (~3 kHz) is what small watch speakers play loudest.
            siren.start(1f, Siren.BEEPER)
        }
    }

    private val siren = Siren()

    private fun forceVolume() {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(android.media.AudioManager.STREAM_ALARM) < max) {
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (_: Exception) {
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Swallow the side buttons while ringing. (The system home button can't be blocked.)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STEM_1 ||
            keyCode == KeyEvent.KEYCODE_STEM_2 || keyCode == KeyEvent.KEYCODE_STEM_3
        ) return true
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back is ignored while ringing")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        vibrator?.cancel()
        siren.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
