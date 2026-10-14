package com.relentless.alarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The screen that shows over the lock screen while ringing. Like Alarmy: the only
 * way to turn the alarm off is to finish every mission on the alarm.
 */
class AlarmActivity : Activity() {
    companion object {
        @Volatile var visible = false

        private const val BARCODE_FALLBACK_AFTER_MS = 5 * 60_000L

        private val PHRASES = listOf(
            "I am awake and I am getting out of bed now",
            "Today is going to be a great day",
            "Snoozing is not an option this morning",
            "I will drink a glass of water right now",
            "My future self will thank me for getting up",
            "Winners do not hit the snooze button",
            "I am stronger than my pillow",
            "The early morning is when I get things done",
            "Get up, stand up, and start moving",
            "I choose discipline over comfort today",
            "One small step out of bed is a giant leap",
            "Open the curtains and let the light in",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val missionHandler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var body: LinearLayout

    private var alarm: Alarm? = null
    private var alarmId = -1
    private var mode = ""
    private var missions = mutableListOf<Mission>()
    /** -1 = the ringing home screen; otherwise index of the mission being done. */
    private var missionIndex = -1
    private var flash = false

    private var clockView: TextView? = null
    private var countdownView: TextView? = null

    private var sensorListener: SensorEventListener? = null
    private var sensorType = 0
    private var scanner: DecoratedBarcodeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        root = FrameLayout(this).apply { setBackgroundColor(Colors.BG) }
        body = vertical(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        setup()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Only restart the screen if a different alarm / mode is ringing; don't wipe mission progress.
        if (AlarmService.currentMode != mode || AlarmService.currentAlarmId != alarmId) setup()
    }

    private fun setup() {
        if (!AlarmService.isRinging) {
            finishAndRemoveTask()
            return
        }
        mode = AlarmService.currentMode
        alarmId = AlarmService.currentAlarmId
        alarm = store.alarm(alarmId)
        missions = (alarm?.missions ?: mutableListOf(Mission(MissionType.MATH))).map { it.copy() }.toMutableList()
        showHome()
    }

    // ------------------------------------------------------------------ home

    private fun showHome() {
        clearMission()
        missionIndex = -1
        AlarmService.missionActive = false
        body.removeAllViews()
        body.gravity = Gravity.CENTER

        clockView = label("", 76f, center = true).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        body.addView(clockView, llp())
        body.addView(label(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), 18f, Colors.SUB, center = true), llp())
        val a = alarm
        if (!a?.label.isNullOrBlank()) body.addView(label(a!!.label, 22f, bold = true, center = true), llp(top = 12))
        body.addView(spacer(40))

        if (mode == Scheduler.MODE_CHECK) {
            body.addView(label("Wake-up check", 30f, bold = true, center = true), llp())
            body.addView(label("Are you really awake? Confirm before time runs out or the full alarm starts again.", 17f, Colors.SUB, center = true), llp(top = 8))
            countdownView = label("", 44f, Colors.WARN, bold = true, center = true)
            body.addView(countdownView, llp(top = 16))
            body.addView(button("I'm awake ✋", Colors.OK, 22f) { done() }, llp(top = 24))
        } else {
            countdownView = null
            val summary = if (missions.isEmpty()) "No mission" else missions.joinToString("  →  ") { "${MissionType.icon(it.type)} ${it.describe()}" }
            body.addView(label(summary, 15f, Colors.SUB, center = true), llp())
            body.addView(button("Dismiss", Colors.ACCENT, 24f) { startMissions() }, llp(top = 20))
            val used = store.snoozesUsed
            val max = a?.maxSnoozes ?: 0
            if (used < max) {
                body.addView(
                    button("Snooze ${a?.snoozeMin ?: 5} min  ·  ${max - used} left", Colors.CARD_HI) {
                        AlarmService.snooze(this)
                        finishAndRemoveTask()
                    },
                    llp(top = 12)
                )
            } else if (max > 0) {
                body.addView(label("No snoozes left. Get up!", 15f, Colors.WARN, center = true), llp(top = 12))
            }
        }
        updateClock()
    }

    private fun startMissions() {
        if (missions.isEmpty()) {
            done()
            return
        }
        missionIndex = 0
        showMission()
    }

    private fun nextMission() {
        missionIndex++
        if (missionIndex >= missions.size) done() else showMission()
    }

    private fun done() {
        clearMission()
        val a = alarm
        val checkSoon = mode == Scheduler.MODE_ALARM && a?.wakeCheck == true && alarmId != Store.PREVIEW_ID
        AlarmService.dismiss(this)
        if (checkSoon) {
            Toast.makeText(this, "Good morning! Wake-up check in ${store.checkDelayMin} min — stay up.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Good morning!", Toast.LENGTH_SHORT).show()
        }
        finishAndRemoveTask()
    }

    // ------------------------------------------------------------------ missions

    private fun showMission() {
        clearMission()
        AlarmService.missionActive = true
        AlarmService.userActive()
        countdownView = null
        clockView = null
        body.removeAllViews()
        body.gravity = Gravity.CENTER_HORIZONTAL
        val m = missions[missionIndex]
        body.addView(
            label("Mission ${missionIndex + 1} of ${missions.size}  ·  ${MissionType.icon(m.type)} ${MissionType.name(m.type)}", 16f, Colors.SUB, center = true),
            llp(top = 8)
        )
        when (m.type) {
            MissionType.MATH -> mathMission(m)
            MissionType.MEMORY -> memoryMission(m)
            MissionType.TYPING -> typingMission(m)
            MissionType.SHAKE -> motionMission(m, steps = false)
            MissionType.STEPS -> motionMission(m, steps = true)
            MissionType.BARCODE -> barcodeMission(m)
            else -> nextMission()
        }
    }

    private fun clearMission() {
        unregisterSensor()
        sensorListener = null
        scanner?.pause()
        scanner = null
        missionHandler.removeCallbacksAndMessages(null)
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun progressText(done: Int, total: Int, unit: String) = "$done / $total $unit"

    // ---- Math

    private fun mathProblem(level: Int): Pair<String, Int> {
        val r = Random
        return when (level) {
            0 -> { val a = r.nextInt(10, 50); val b = r.nextInt(2, 10); "$a + $b" to a + b }
            1 -> { val a = r.nextInt(10, 90); val b = r.nextInt(10, 90); "$a + $b" to a + b }
            2 -> { val a = r.nextInt(12, 30); val b = r.nextInt(3, 10); val c = r.nextInt(10, 100); "$a × $b + $c" to a * b + c }
            3 -> { val a = r.nextInt(12, 40); val b = r.nextInt(11, 20); val c = r.nextInt(10, 100); "$a × $b + $c" to a * b + c }
            else -> { val a = r.nextInt(21, 50); val b = r.nextInt(13, 40); val c = r.nextInt(100, 1000); "$a × $b + $c" to a * b + c }
        }
    }

    private fun mathMission(m: Mission) {
        var solved = 0
        var input = ""
        var problem = mathProblem(m.level)
        val progress = label(progressText(0, m.count, "solved"), 16f, Colors.SUB, center = true)
        val problemView = label(problem.first + " = ?", 40f, bold = true, center = true)
        val answerView = label("", 40f, Colors.ACCENT, center = true).apply {
            background = rounded(Colors.CARD)
            setPadding(0, dp(10), 0, dp(10))
        }
        body.addView(progress, llp(top = 8))
        body.addView(problemView, llp(top = 24))
        body.addView(answerView, llp(top = 16))

        fun refresh() {
            answerView.text = input.ifEmpty { " " }
        }

        fun submit() {
            if (input.isEmpty()) return
            if (input.toIntOrNull() == problem.second) {
                solved++
                progress.text = progressText(solved, m.count, "solved")
                if (solved >= m.count) {
                    nextMission()
                    return
                }
            } else {
                Toast.makeText(this, "Wrong — new problem", Toast.LENGTH_SHORT).show()
            }
            problem = mathProblem(m.level)
            problemView.text = problem.first + " = ?"
            input = ""
            refresh()
        }

        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        val pad = vertical()
        for (row in keys) {
            val r = horizontal()
            for (k in row) {
                val color = when (k) {
                    "✓" -> Colors.OK
                    "⌫" -> Colors.CARD_HI
                    else -> Colors.CARD
                }
                r.addView(button(k, color, 26f) {
                    when (k) {
                        "⌫" -> input = input.dropLast(1)
                        "✓" -> submit()
                        else -> if (input.length < 7) input += k
                    }
                    refresh()
                }, llp(0, dp(68), 1f, side = 4).apply { bottomMargin = dp(8) })
            }
            pad.addView(r, llp())
        }
        body.addView(pad, llp(top = 20))
        refresh()
    }

    // ---- Memory

    private fun memoryMission(m: Mission) {
        val (n, tiles) = when (m.level) {
            0 -> 3 to 3
            1 -> 3 to 4
            2 -> 4 to 5
            3 -> 4 to 7
            else -> 5 to 9
        }
        var round = 0
        var pattern = setOf<Int>()
        val found = mutableSetOf<Int>()
        var accepting = false

        val status = label("", 18f, center = true)
        val progress = label(progressText(0, m.count, "rounds"), 16f, Colors.SUB, center = true)
        body.addView(progress, llp(top = 8))
        body.addView(status, llp(top = 16))

        val size = minOf((resources.displayMetrics.widthPixels - dp(64)) / n, dp(84))
        val cells = ArrayList<View>()
        val grid = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        fun paint(i: Int, color: Int) {
            cells[i].background = rounded(color, 10)
        }

        var startRound: () -> Unit = {}
        startRound = {
            accepting = false
            found.clear()
            pattern = (0 until n * n).shuffled().take(tiles).toSet()
            for (i in 0 until n * n) paint(i, if (i in pattern) Colors.ACCENT else Colors.CARD)
            status.text = "Memorize the red tiles…"
            missionHandler.postDelayed({
                for (i in 0 until n * n) paint(i, Colors.CARD)
                accepting = true
                status.text = "Tap the tiles that were red"
            }, 1200L + tiles * 250L)
        }

        for (rIdx in 0 until n) {
            val row = horizontal().apply { gravity = Gravity.CENTER }
            for (c in 0 until n) {
                val i = rIdx * n + c
                val cell = View(this)
                cell.setOnClickListener {
                    if (!accepting || i in found) return@setOnClickListener
                    if (i in pattern) {
                        found.add(i)
                        paint(i, Colors.OK)
                        if (found.size == pattern.size) {
                            accepting = false
                            round++
                            progress.text = progressText(round, m.count, "rounds")
                            if (round >= m.count) missionHandler.postDelayed({ nextMission() }, 400)
                            else missionHandler.postDelayed({ startRound() }, 700)
                        }
                    } else {
                        accepting = false
                        paint(i, Colors.FLASH)
                        status.text = "Wrong! New pattern…"
                        missionHandler.postDelayed({ startRound() }, 1000)
                    }
                }
                cells.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(size - dp(8), size - dp(8)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            grid.addView(row)
        }
        body.addView(grid, llp(top = 24))
        startRound()
    }

    // ---- Typing

    private fun normalize(s: String) =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    private fun typingMission(m: Mission) {
        var done = 0
        val phrases = PHRASES.shuffled()
        val progress = label(progressText(0, m.count, "phrases"), 16f, Colors.SUB, center = true)
        val phraseView = label("“${phrases[0]}”", 24f, bold = true, center = true)
        val input = EditText(this).apply {
            setTextColor(Colors.TEXT)
            setHintTextColor(Colors.SUB)
            hint = "Type the phrase above"
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(Colors.CARD)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        fun submit() {
            if (normalize(input.text.toString()) == normalize(phrases[done % phrases.size])) {
                done++
                progress.text = progressText(done, m.count, "phrases")
                if (done >= m.count) {
                    nextMission()
                    return
                }
                phraseView.text = "“${phrases[done % phrases.size]}”"
                input.setText("")
            } else {
                Toast.makeText(this, "Not quite — check it and try again", Toast.LENGTH_SHORT).show()
            }
        }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        body.addView(progress, llp(top = 8))
        body.addView(phraseView, llp(top = 24))
        body.addView(input, llp(top = 24))
        body.addView(button("Submit", Colors.OK) { submit() }, llp(top = 12))
        input.requestFocus()
        missionHandler.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    // ---- Shake / Steps

    private fun motionMission(m: Mission, steps: Boolean) {
        var count = 0
        var last = 0L
        val big = label("${m.count}", 96f, Colors.ACCENT, bold = true, center = true)
        val sub = label(if (steps) "steps to go — get up and walk" else "shakes to go — shake it hard!", 18f, Colors.SUB, center = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = m.count
            progress = 0
        }
        body.addView(spacer(40))
        body.addView(big, llp())
        body.addView(sub, llp())
        body.addView(bar, llp(top = 24))

        fun bump() {
            count++
            AlarmService.userActive()
            big.text = "${(m.count - count).coerceAtLeast(0)}"
            bar.progress = count
            if (count >= m.count) {
                unregisterSensor()
                sensorListener = null
                missionHandler.postDelayed({ nextMission() }, 300)
            }
        }

        val sm = getSystemService(SensorManager::class.java)
        val canUseStepSensor = steps && sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)

        if (canUseStepSensor) {
            listen(Sensor.TYPE_STEP_DETECTOR) { bump() }
        } else {
            val threshold = if (steps) 1.3f else 2.2f
            val gap = if (steps) 330L else 180L
            listen(Sensor.TYPE_ACCELEROMETER) { e ->
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
                    SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                if (g > threshold && now - last > gap) {
                    last = now
                    bump()
                }
            }
        }
    }

    private fun listen(type: Int, onEvent: (SensorEvent) -> Unit) {
        sensorType = type
        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = onEvent(event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        registerSensor()
    }

    private fun registerSensor() {
        val l = sensorListener ?: return
        val sm = getSystemService(SensorManager::class.java)
        val s = sm.getDefaultSensor(sensorType) ?: return
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterSensor() {
        sensorListener?.let { getSystemService(SensorManager::class.java).unregisterListener(it) }
    }

    // ---- Barcode

    private fun replaceWithMath(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        missions[missionIndex] = Mission(MissionType.MATH, level = 3, count = 8)
        showMission()
    }

    private fun barcodeMission(m: Mission) {
        if (m.code.isEmpty()) {
            replaceWithMath("No barcode registered — doing hard math instead")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            replaceWithMath("Camera permission missing — doing hard math instead")
            return
        }
        body.addView(label("Go and scan:", 18f, Colors.SUB, center = true), llp(top = 8))
        body.addView(label(m.note.ifBlank { "your registered barcode" }, 26f, bold = true, center = true), llp(top = 4))
        val view = DecoratedBarcodeView(this)
        view.setStatusText("")
        var lastWrong = 0L
        view.decodeContinuous { result ->
            AlarmService.userActive()
            if (result.text == m.code) {
                view.pause()
                scanner = null
                nextMission()
            } else if (System.currentTimeMillis() - lastWrong > 2500) {
                lastWrong = System.currentTimeMillis()
                Toast.makeText(this, "That's not the right barcode", Toast.LENGTH_SHORT).show()
            }
        }
        body.addView(view, llp(MATCH, dp(340), top = 16))
        scanner = view
        view.resume()

        val fallback = button("", Colors.CARD_HI, 15f) {
            if (System.currentTimeMillis() - AlarmService.ringStartedAt >= BARCODE_FALLBACK_AFTER_MS) {
                replaceWithMath("OK — 8 hard math problems instead")
            }
        }
        body.addView(fallback, llp(top = 16))
        val updater = object : Runnable {
            override fun run() {
                val left = BARCODE_FALLBACK_AFTER_MS - (System.currentTimeMillis() - AlarmService.ringStartedAt)
                fallback.text = if (left <= 0) "Can't find it? Do hard math instead"
                else "Can't find it? Math fallback unlocks in ${(left + 999) / 1000}s"
                missionHandler.postDelayed(this, 1000)
            }
        }
        updater.run()
    }

    // ------------------------------------------------------------------ lifecycle

    private val ticker = object : Runnable {
        override fun run() {
            if (!AlarmService.isRinging) {
                finishAndRemoveTask()
                return
            }
            if (AlarmService.currentMode != mode || AlarmService.currentAlarmId != alarmId) setup()
            updateClock()
            if (missionIndex < 0 && mode == Scheduler.MODE_ALARM) {
                flash = !flash
                root.setBackgroundColor(if (flash) Colors.FLASH else Colors.BG)
            } else {
                root.setBackgroundColor(Colors.BG)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun updateClock() {
        clockView?.text = fmtTime(
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
        )
        countdownView?.let {
            val left = store.checkWindowSec - (System.currentTimeMillis() - AlarmService.ringStartedAt) / 1000
            it.text = "${left.coerceAtLeast(0)}s"
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        registerSensor()
        scanner?.resume()
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(ticker)
        unregisterSensor()
        scanner?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        missionHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        AlarmService.userActive()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS -> return true
        }
        AlarmService.userActive()
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Back does nothing while ringing")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Intentionally ignored: finish your mission.
    }
}
