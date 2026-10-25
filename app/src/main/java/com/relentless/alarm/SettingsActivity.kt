package com.relentless.alarm

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class SettingsActivity : Activity() {
    companion object {
        private const val REQ_RINGTONE = 20
    }

    private lateinit var store: Store
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        content = vertical(20)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(titleBar("Settings"), llp())

        section("Sound")
        group(
            row("Alarm sound", store.soundName) { pickSound() },
            switchRow("Flashlight strobe", store.flashlight) { store.flashlight = it },
        )

        section("Wake-up check")
        group(
            row("Comes after", "${store.checkDelayMin} min") {
                val opts = intArrayOf(2, 3, 5, 10, 15, 20)
                choose("Wake-up check after dismissing", opts.map { "$it minutes" }) {
                    store.checkDelayMin = opts[it]
                    render()
                }
            },
            row("Time to answer", "${store.checkWindowSec} s") {
                val opts = intArrayOf(30, 60, 90, 120, 180)
                choose("Seconds to confirm you're awake", opts.map { "$it seconds" }) {
                    store.checkWindowSec = opts[it]
                    render()
                }
            },
        )

        section("Gemini & voice")
        group(
            switchRow("Follow Gemini / Google Clock alarms", store.followClock) {
                store.followClock = it
                if (it) ClockFollowReceiver.check(this)
            },
        )
        content.addView(
            label(
                "Say \"Hey Google, set an alarm for 7 AM\". If Gemini asks which app, pick Wake Up Holmes. " +
                    "If it puts the alarm in Google Clock instead, this switch adds a matching Wake Up Holmes alarm " +
                    "(alarms within the next 24 hours) using your first alarm's missions.",
                13f, Colors.DIM
            ),
            llp(top = 6)
        )

        section("Uninstall protection")
        val guarded = Guard.isOn(this)
        group(
            row("Protection", if (guarded) "✓ On" else "Off", if (guarded) Colors.OK else Colors.SUB) {},
            if (guarded) {
                row("Turn off (prove you're awake)", "›") {
                    AlertDialog.Builder(this)
                        .setTitle("Turn off protection?")
                        .setMessage("You'll have to solve 10 very hard math problems and type 3 phrases. Then protection turns off and you can uninstall.")
                        .setPositiveButton("Start") { _, _ -> Guard.startGauntlet(this) }
                        .setNegativeButton("Keep it on", null)
                        .show()
                }
            } else {
                row("Turn on", "›") {
                    try {
                        startActivity(Guard.enableIntent(this))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Couldn't open the device admin screen", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
        content.addView(
            label(
                "While it's on, Android won't let you uninstall the app. If you try to switch it off in Android's settings, " +
                    "the phone locks and the full alarm goes off until you finish your missions.",
                13f, Colors.DIM
            ),
            llp(top = 6)
        )

        section("Galaxy Watch")
        val watchStatus = label("Checking…", 15f, Colors.SUB)
        val watchRow = horizontal().apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            addView(label("Watch", 16f), llp(0, WRAP, 1f))
            addView(watchStatus)
        }
        val testRow = row("Test buzz on the watch", "Send ›") {
            WatchBridge.send(
                this, WatchBridge.TEST,
                org.json.JSONObject().put("mode", "alarm").put("label", "Test").put("missions", "🔍 Find: your item").toString()
            )
            Toast.makeText(this, "Sent. Your watch should buzz for a few seconds.", Toast.LENGTH_SHORT).show()
        }
        group(watchRow, testRow)
        content.addView(
            label("Install the Wake Up Holmes watch app (see the README) and open it once on the watch.", 13f, Colors.DIM),
            llp(top = 6)
        )
        WatchBridge.connectedWatches(this) { names ->
            watchStatus.text = when {
                names == null -> "Wear OS not available"
                names.isEmpty() -> "Not connected"
                else -> "✓ ${names.joinToString()}"
            }
            watchStatus.setTextColor(if (names.isNullOrEmpty()) Colors.SUB else Colors.OK)
        }

        section("Make sure it works")
        content.addView(button("🔇  Silent test: try the missions now", Colors.CARD_HI, 16f) { silentTest() }, llp(top = 8))
        content.addView(button("🔊  Ring a test alarm in 10 seconds", Colors.CARD_HI, 16f) { testAlarm() }, llp(top = 8))
        val checks = Setup.checks(this)
        val rows = checks.map { c ->
            row(c.title, if (c.ok) "✓" else "Fix ›", if (c.ok) Colors.OK else Colors.WARN) { if (!c.ok) c.fix(this) }
        }
        group(*rows.toTypedArray())

        section("Tips")
        content.addView(
            label(
                "• Find the item is the strongest mission. Register something in another room, like toothpaste or coffee.\n\n" +
                    "• Chain missions (for example Find the item, then Math) and allow 0 or 1 snoozes.\n\n" +
                    "• While you work on a mission the sound goes quiet. Stop, and it comes back at full volume.\n\n" +
                    "• Keep the phone charging and out of arm's reach.\n\n" +
                    "• Samsung: Settings › Battery › Background usage limits. Add this app to \"Never sleeping apps\".",
                14f, Colors.SUB
            ),
            llp(top = 8)
        )
        content.addView(label("Font: Playfair Display (SIL Open Font License)", 12f, Colors.DIM), llp(top = 24))
        content.addView(spacer(24))
    }

    private fun section(s: String) {
        content.addView(label(s.uppercase(), 12f, Colors.SUB, bold = true).apply { letterSpacing = 0.12f }, llp(top = 26))
    }

    /** Rows stacked inside one rounded card, separated by hairlines. */
    private fun group(vararg rows: LinearLayout) {
        val box = vertical().apply { background = rounded(Colors.CARD) }
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(android.view.View(this).apply { setBackgroundColor(Colors.LINE) }, llp(MATCH, 1, side = 16))
            box.addView(r, llp())
        }
        content.addView(box, llp(top = 8))
    }

    private fun row(title: String, value: String, valueColor: Int = Colors.ACCENT, onClick: () -> Unit) =
        horizontal().apply {
            background = pressable(Colors.CARD)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setOnClickListener { onClick() }
            addView(label(title, 16f), llp(0, WRAP, 1f))
            addView(label(value, 15f, valueColor))
        }

    private fun switchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) =
        horizontal().apply {
            setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(label(title, 16f), llp(0, WRAP, 1f))
            addView(Switch(this@SettingsActivity).apply {
                isChecked = value
                setOnCheckedChangeListener { _, c -> onChange(c) }
            })
        }

    private fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
    }

    /** Full alarm screen + your first alarm's missions, but no sound, vibration, flashlight or watch. */
    private fun silentTest() {
        val template = store.alarms().firstOrNull()?.deepCopy() ?: Alarm(id = Store.PREVIEW_ID)
        store.preview = template.apply {
            id = Store.PREVIEW_ID
            label = "Silent test"
        }
        store.snoozesUsed = 0
        AlarmService.start(this, Store.PREVIEW_ID, Scheduler.MODE_ALARM, silent = true)
    }

    private fun testAlarm() {
        val template = store.alarms().firstOrNull()?.deepCopy() ?: Alarm(id = Store.PREVIEW_ID)
        store.preview = template.apply {
            id = Store.PREVIEW_ID
            label = "Test alarm"
        }
        store.snoozesUsed = 0
        Scheduler.scheduleExtra(this, Store.PREVIEW_ID, Scheduler.MODE_ALARM, System.currentTimeMillis() + 10_000)
        Toast.makeText(this, "Rings in 10 seconds. Lock your phone now!", Toast.LENGTH_LONG).show()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
