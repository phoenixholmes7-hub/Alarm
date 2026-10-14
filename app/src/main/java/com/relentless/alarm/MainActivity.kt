package com.relentless.alarm

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private var fixesOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        AlarmService.ensureChannel(this)

        val root = FrameLayout(this)
        content = vertical(20).apply { setPadding(dp(20), dp(16), dp(20), dp(110)) }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        val add = button("+   New alarm", Colors.ACCENT, 18f) {
            startActivity(Intent(this, EditActivity::class.java))
        }.apply { elevation = dp(6).toFloat() }
        root.addView(add, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(20), 0, dp(20), dp(20))
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Re-arm everything every time the app is opened, just in case.
        Scheduler.rescheduleAll(this)
        render()
    }

    private fun render() {
        content.removeAllViews()

        // Header: logo + wordmark + settings
        val header = horizontal()
        header.addView(logo(40))
        header.addView(wordmark(24f), llp(0, WRAP, 1f).apply { leftMargin = dp(12) })
        header.addView(label("⚙", 24f, Colors.SUB).apply {
            setPadding(dp(12), dp(6), dp(4), dp(6))
            contentDescription = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        content.addView(header, llp())

        renderNext()
        renderFixes()

        val alarms = store.alarms()
        if (alarms.isEmpty()) {
            renderEmpty()
        } else {
            content.addView(label("ALARMS", 12f, Colors.SUB, bold = true).apply { letterSpacing = 0.12f }, llp(top = 28))
            for (a in alarms) content.addView(alarmCard(a), llp(top = 10))
        }
    }

    private fun renderNext() {
        val next = Scheduler.nextAlarm(this) ?: return
        val (a, at) = next
        val box = vertical(20).apply { background = rounded(Colors.NAVY, 20) }
        box.addView(label("NEXT ALARM", 12f, 0xB3FFFFFF.toInt(), bold = true).apply { letterSpacing = 0.12f }, llp())
        box.addView(
            label("in ${untilText(at - System.currentTimeMillis())}", 34f).apply { typeface = holmesFont() },
            llp(top = 2)
        )
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(at))
        val detail = buildString {
            append("$day · ${fmtTime(a.hour, a.minute)}")
            if (a.label.isNotBlank()) append(" · ${a.label}")
        }
        box.addView(label(detail, 15f, 0xCCFFFFFF.toInt()), llp(top = 2))
        content.addView(box, llp(top = 20))
    }

    private fun renderFixes() {
        val missing = Setup.checks(this).filter { !it.ok }
        if (missing.isEmpty()) return
        val box = vertical(16).apply {
            background = pressable(0xFF2A2210.toInt())
            setOnClickListener {
                fixesOpen = !fixesOpen
                render()
            }
        }
        val head = horizontal()
        head.addView(
            label("⚠  ${missing.size} setting${if (missing.size > 1) "s" else ""} could stop your alarm", 15f, Colors.WARN, bold = true),
            llp(0, WRAP, 1f)
        )
        head.addView(label(if (fixesOpen) "Hide" else "Fix", 15f, Colors.WARN))
        box.addView(head, llp())
        if (fixesOpen) {
            for (c in missing) {
                val row = vertical(12).apply {
                    background = pressable(Colors.CARD_HI, 12)
                    setOnClickListener { c.fix(this@MainActivity) }
                }
                row.addView(label("${c.title}  ›", 15f, bold = true), llp())
                row.addView(label(c.why, 13f, Colors.SUB), llp(top = 2))
                box.addView(row, llp(top = 10))
            }
        }
        content.addView(box, llp(top = 12))
    }

    private fun renderEmpty() {
        val box = vertical(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(logo(88))
        box.addView(label("No alarms yet", 26f, center = true).apply { typeface = holmesFont() }, llp(top = 16))
        box.addView(
            label("Set your first alarm and pick a mission.\nThe alarm stays on until the mission is done.", 15f, Colors.SUB, center = true),
            llp(top = 6)
        )
        content.addView(box, llp(top = 48))
    }

    private fun alarmCard(a: Alarm): LinearLayout {
        val on = a.enabled
        val card = horizontal().apply {
            background = pressable(Colors.CARD, 20)
            setPadding(dp(20), dp(16), dp(14), dp(16))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, a.id))
            }
        }
        val left = vertical()
        left.addView(label(bigTime(a.hour, a.minute), 40f, if (on) Colors.TEXT else Colors.SUB).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, llp())

        // Day letters, the active ones highlighted
        val days = horizontal()
        if (a.days == 0) {
            days.addView(label("Once", 13f, if (on) Colors.ACCENT else Colors.DIM, bold = true))
        } else {
            "MTWTFSS".forEachIndexed { i, ch ->
                val active = a.days and (1 shl i) != 0
                days.addView(
                    label("$ch", 13f, if (active && on) Colors.ACCENT else if (active) Colors.SUB else Colors.DIM, bold = active),
                    llp(WRAP, WRAP).apply { rightMargin = dp(7) }
                )
            }
        }
        if (a.label.isNotBlank()) {
            days.addView(label("·  ${a.label}", 13f, Colors.SUB), llp(WRAP, WRAP).apply { leftMargin = dp(4) })
        }
        left.addView(days, llp(top = 2))

        val missions = if (a.missions.isEmpty()) "No mission" else a.missions.joinToString("   ") { MissionType.icon(it.type) }
        left.addView(label(missions, 15f, if (on) Colors.TEXT else Colors.DIM), llp(top = 8))
        card.addView(left, llp(0, WRAP, 1f))

        card.addView(Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                a.enabled = checked
                store.save(a)
                Scheduler.schedule(this@MainActivity, a)
                if (checked) {
                    Toast.makeText(
                        this@MainActivity,
                        "Alarm set for ${untilText(a.nextTrigger() - System.currentTimeMillis())} from now",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                render()
            }
        })
        return card
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
