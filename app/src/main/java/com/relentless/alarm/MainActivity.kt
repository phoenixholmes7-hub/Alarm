package com.relentless.alarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val REQ_RINGTONE = 20
    }

    private lateinit var store: Store
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        AlarmService.ensureChannel(this)
        content = vertical(20)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        // Re-arm everything every time the app is opened, just in case.
        Scheduler.rescheduleAll(this)
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label("Relentless Alarm", 28f, bold = true), llp())
        val next = Scheduler.nextAlarm(this)
        content.addView(
            label(
                if (next == null) "No alarms on" else "Next alarm in ${untilText(next.second - System.currentTimeMillis())}",
                16f, Colors.SUB
            ),
            llp(top = 2)
        )

        renderChecklist()

        // Alarms
        content.addView(section("Alarms"), llp(top = 24))
        val alarms = store.alarms()
        if (alarms.isEmpty()) {
            content.addView(label("No alarms yet. Add one below.", 15f, Colors.SUB), llp(top = 8))
        }
        for (a in alarms) content.addView(alarmCard(a), llp(top = 10))
        content.addView(button("+  New alarm", Colors.ACCENT, 18f) {
            startActivity(Intent(this, EditActivity::class.java))
        }, llp(top = 14))
        content.addView(button("Test: ring in 10 seconds (lock your phone now)", Colors.CARD_HI, 15f) { testAlarm() }, llp(top = 10))

        // Settings
        content.addView(section("Settings for all alarms"), llp(top = 28))
        content.addView(settingRow("Alarm sound", store.soundName) { pickSound() }, llp(top = 8))
        content.addView(switchRow("Flashlight strobe while ringing", store.flashlight) { store.flashlight = it }, llp(top = 8))
        content.addView(settingRow("Wake-up check comes after", "${store.checkDelayMin} min") {
            val opts = intArrayOf(2, 3, 5, 10, 15, 20)
            choose("Wake-up check after dismissing", opts.map { "$it minutes" }) {
                store.checkDelayMin = opts[it]
                render()
            }
        }, llp(top = 8))
        content.addView(settingRow("Time to answer wake-up check", "${store.checkWindowSec} s") {
            val opts = intArrayOf(30, 60, 90, 120, 180)
            choose("Seconds to confirm you're awake", opts.map { "$it seconds" }) {
                store.checkWindowSec = opts[it]
                render()
            }
        }, llp(top = 8))

        content.addView(section("Tips"), llp(top = 28))
        content.addView(
            label(
                "• Use the Barcode mission: register your toothpaste or coffee — you physically have to get up.\n" +
                    "• Stack missions (e.g. Barcode → Math) and keep snoozes at 0 or 1.\n" +
                    "• The sound gets quiet while you're doing a mission, and comes back at full blast if you stop for 12 seconds.\n" +
                    "• Volume buttons don't work while ringing, and alarm volume is forced to max.\n" +
                    "• Keep the phone charging and out of arm's reach.\n" +
                    "• Samsung / Xiaomi / OnePlus etc.: also exclude this app from \"sleeping apps\" / enable \"Autostart\" — see dontkillmyapp.com.\n" +
                    "• Press Test above at least once with the phone locked to make sure it works on your phone.",
                14f, Colors.SUB
            ),
            llp(top = 8)
        )
        content.addView(spacer(32))
    }

    private fun section(s: String) = label(s, 14f, Colors.SUB, bold = true)

    private fun alarmCard(a: Alarm): LinearLayout {
        val card = horizontal().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(18), dp(14), dp(12), dp(14))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, a.id))
            }
        }
        val left = vertical()
        left.addView(label(fmtTime(a.hour, a.minute), 36f, if (a.enabled) Colors.TEXT else Colors.SUB), llp())
        val desc = buildString {
            append(a.daysText())
            if (a.label.isNotBlank()) append("  ·  ${a.label}")
        }
        left.addView(label(desc, 14f, Colors.SUB), llp())
        val missions = if (a.missions.isEmpty()) "No mission" else a.missions.joinToString(" ") { MissionType.icon(it.type) }
        left.addView(label(missions, 18f), llp(top = 4))
        card.addView(left, llp(0, WRAP, 1f))
        card.addView(Switch(this).apply {
            isChecked = a.enabled
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

    private fun settingRow(title: String, value: String, onClick: () -> Unit): LinearLayout {
        val row = horizontal().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
        }
        row.addView(label(title, 16f), llp(0, WRAP, 1f))
        row.addView(label(value, 15f, Colors.ACCENT))
        return row
    }

    private fun switchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = horizontal().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        row.addView(label(title, 16f), llp(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return row
    }

    private fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
    }

    // ------------------------------------------------------------------ setup checklist

    private class Check(val title: String, val why: String, val ok: Boolean, val fix: () -> Unit)

    private fun checks(): List<Check> {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val list = mutableListOf<Check>()
        list += Check(
            "Notifications", "Needed to show the alarm over the lock screen.",
            nm.areNotificationsEnabled()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        list += Check("Exact alarms", "Lets the alarm fire at the exact minute.", Scheduler.canScheduleExact(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            list += Check("Full-screen alarm", "Lets the alarm take over the screen when locked.", nm.canUseFullScreenIntent()) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        list += Check(
            "Unrestricted battery", "Stops Android from killing the alarm to save battery.",
            pm.isIgnoringBatteryOptimizations(packageName)
        ) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        list += Check(
            "Display over other apps", "Lets the alarm screen pop back up if you leave it while ringing.",
            Settings.canDrawOverlays(this)
        ) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        return list
    }

    private fun renderChecklist() {
        val list = checks()
        val missing = list.filter { !it.ok }
        val box = card()
        if (missing.isEmpty()) {
            box.addView(label("✅  All set — nothing can stop this alarm.", 16f, Colors.OK, bold = true), llp())
        } else {
            box.addView(label("⚠️  ${missing.size} setting(s) could stop your alarm", 17f, Colors.WARN, bold = true), llp())
            box.addView(label("Tap each one to fix it:", 14f, Colors.SUB), llp(top = 2))
            for (c in missing) {
                val row = vertical(12).apply {
                    background = rounded(Colors.CARD_HI, 12)
                    setOnClickListener { c.fix() }
                }
                row.addView(label("✗  ${c.title}  →  Fix", 16f, bold = true), llp())
                row.addView(label(c.why, 13f, Colors.SUB), llp(top = 2))
                box.addView(row, llp(top = 10))
            }
        }
        content.addView(box, llp(top = 16))
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------ actions

    private fun testAlarm() {
        val template = store.alarms().firstOrNull()?.deepCopy() ?: Alarm(id = Store.PREVIEW_ID)
        store.preview = template.apply {
            id = Store.PREVIEW_ID
            label = "Test alarm"
        }
        store.snoozesUsed = 0
        Scheduler.scheduleExtra(this, Store.PREVIEW_ID, Scheduler.MODE_ALARM, System.currentTimeMillis() + 10_000)
        Toast.makeText(this, "Rings in 10 seconds — lock your phone now!", Toast.LENGTH_LONG).show()
    }

    private fun pickSound() {
        choose("Alarm sound", listOf("Built-in siren (harshest, recommended)", "Choose a sound from your phone…")) {
            if (it == 0) {
                store.sound = Store.SOUND_SIREN
                store.soundName = "Built-in siren"
                render()
            } else {
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_RINGTONE)
            }
        }
    }

    @Deprecated("Uses startActivityForResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RINGTONE || resultCode != RESULT_OK) return
        val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return
        store.sound = uri.toString()
        store.soundName = try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Custom sound"
        } catch (e: Exception) {
            "Custom sound"
        }
        render()
    }
}
