package com.relentless.alarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Create / edit one alarm — time, repeat days, missions, snooze and wake-up check. */
class EditActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_SCAN = 10
        private const val MAX_MISSIONS = 5
    }

    private lateinit var store: Store
    private lateinit var alarm: Alarm
    private var isNew = true
    private lateinit var content: LinearLayout
    private lateinit var timeView: TextView
    private lateinit var missionList: LinearLayout
    private val dayViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val existing = if (id > 0) store.alarm(id) else null
        isNew = existing == null
        alarm = existing?.deepCopy() ?: Alarm(id = store.newId())
        build()
    }

    private fun build() {
        content = vertical(20)
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        content.addView(label(if (isNew) "New alarm" else "Edit alarm", 22f, bold = true), llp())

        // Time
        timeView = label("", 64f, Colors.TEXT, center = true).apply {
            background = rounded(Colors.CARD)
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { pickTime() }
        }
        content.addView(timeView, llp(top = 16))
        refreshTime()

        // Days
        content.addView(section("Repeat"), llp(top = 20))
        val dayRow = horizontal()
        val letters = arrayOf("M", "T", "W", "T", "F", "S", "S")
        for (i in 0..6) {
            val v = label(letters[i], 16f, bold = true, center = true)
            v.setOnClickListener {
                alarm.days = alarm.days xor (1 shl i)
                refreshDays()
            }
            dayViews.add(v)
            dayRow.addView(v, llp(0, dp(44), 1f, side = 3))
        }
        content.addView(dayRow, llp(top = 8))
        refreshDays()

        // Label
        content.addView(section("Label"), llp(top = 20))
        val labelEdit = EditText(this).apply {
            setText(alarm.label)
            hint = "e.g. Work, Gym"
            setTextColor(Colors.TEXT)
            setHintTextColor(Colors.SUB)
            background = rounded(Colors.CARD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    alarm.label = s?.toString() ?: ""
                }
            })
        }
        content.addView(labelEdit, llp(top = 8))

        // Missions
        content.addView(section("Missions (all must be completed to turn it off)"), llp(top = 20))
        missionList = vertical()
        content.addView(missionList, llp(top = 8))
        refreshMissions()

        // Snooze
        content.addView(section("Snooze"), llp(top = 20))
        content.addView(settingRow("Snoozes allowed", { if (alarm.maxSnoozes == 0) "Off" else "${alarm.maxSnoozes}×" }) { row ->
            val opts = intArrayOf(0, 1, 2, 3, 5)
            choose("Snoozes allowed", opts.map { if (it == 0) "Off — no snoozing" else "$it" }) {
                alarm.maxSnoozes = opts[it]
                row()
            }
        }, llp(top = 8))
        content.addView(settingRow("Snooze length", { "${alarm.snoozeMin} min" }) { row ->
            val opts = intArrayOf(1, 3, 5, 10, 15)
            choose("Snooze length", opts.map { "$it minutes" }) {
                alarm.snoozeMin = opts[it]
                row()
            }
        }, llp(top = 8))

        // Wake-up check
        content.addView(section("Wake-up check"), llp(top = 20))
        val checkRow = horizontal().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        checkRow.addView(
            label("${store.checkDelayMin} min after you dismiss, make sure you're still awake. Miss it and the alarm rings again.", 14f, Colors.SUB),
            llp(0, WRAP, 1f)
        )
        checkRow.addView(Switch(this).apply {
            isChecked = alarm.wakeCheck
            setOnCheckedChangeListener { _, c -> alarm.wakeCheck = c }
        })
        content.addView(checkRow, llp(top = 8))

        // Actions
        content.addView(button("Preview (rings now)", Colors.CARD_HI) { preview() }, llp(top = 28))
        content.addView(button("Save", Colors.ACCENT, 20f) { save() }, llp(top = 12))
        if (!isNew) {
            content.addView(button("Delete alarm", Colors.CARD) { confirmDelete() }, llp(top = 12))
        }
        content.addView(spacer(24))
    }

    private fun section(s: String) = label(s, 14f, Colors.SUB, bold = true)

    private fun settingRow(title: String, value: () -> String, onClick: (refresh: () -> Unit) -> Unit): LinearLayout {
        val row = horizontal().apply {
            background = rounded(Colors.CARD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val v = label(value(), 16f, Colors.ACCENT)
        row.addView(label(title, 16f), llp(0, WRAP, 1f))
        row.addView(v)
        row.setOnClickListener { onClick { v.text = value() } }
        return row
    }

    private fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
    }

    private fun refreshTime() {
        timeView.text = fmtTime(alarm.hour, alarm.minute)
    }

    private fun refreshDays() {
        dayViews.forEachIndexed { i, v ->
            val on = alarm.days and (1 shl i) != 0
            v.background = rounded(if (on) Colors.ACCENT else Colors.CARD, 22)
            v.setTextColor(if (on) Colors.TEXT else Colors.SUB)
        }
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, m ->
            alarm.hour = h
            alarm.minute = m
            refreshTime()
        }, alarm.hour, alarm.minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun refreshMissions() {
        missionList.removeAllViews()
        if (alarm.missions.isEmpty()) {
            missionList.addView(label("No mission — the alarm turns off with one tap. Not recommended!", 14f, Colors.WARN), llp())
        }
        alarm.missions.forEachIndexed { i, m ->
            val row = horizontal().apply {
                background = rounded(Colors.CARD)
                setPadding(dp(16), dp(12), dp(8), dp(12))
                setOnClickListener { configure(m, isNew = false) }
            }
            row.addView(label(MissionType.icon(m.type), 24f), llp(WRAP, WRAP).apply { rightMargin = dp(12) })
            row.addView(label(m.describe(), 16f), llp(0, WRAP, 1f))
            row.addView(label("✕", 20f, Colors.SUB).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener {
                    alarm.missions.removeAt(i)
                    refreshMissions()
                }
            })
            missionList.addView(row, llp(top = if (i == 0) 0 else 8))
        }
        if (alarm.missions.size < MAX_MISSIONS) {
            missionList.addView(button("+ Add mission", Colors.CARD_HI, 16f) { addMission() }, llp(top = 8))
        }
    }

    private fun addMission() {
        val types = MissionType.ALL
        choose("Choose a mission", types.map { "${MissionType.icon(it)}  ${MissionType.name(it)} — ${MissionType.blurb(it)}" }) {
            configure(Mission(types[it]), isNew = true)
        }
    }

    private fun configure(m: Mission, isNew: Boolean) {
        when (m.type) {
            MissionType.BARCODE -> {
                pendingBarcode = m
                pendingIsNew = isNew
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
            }
            else -> {
                if (m.type == MissionType.STEPS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 2)
                }
                val opts = MissionType.countOptions(m.type)
                choose("How many ${MissionType.unit(m.type)}?", opts.map { "$it" }) { ci ->
                    m.count = opts[ci]
                    if (MissionType.hasLevel(m.type)) {
                        choose("Difficulty", MissionType.levelNames(m.type).toList()) { li ->
                            m.level = li
                            commitMission(m, isNew)
                        }
                    } else {
                        commitMission(m, isNew)
                    }
                }
            }
        }
    }

    private var pendingBarcode: Mission? = null
    private var pendingIsNew = false

    private fun commitMission(m: Mission, isNew: Boolean) {
        if (isNew) alarm.missions.add(m)
        refreshMissions()
    }

    @Deprecated("Uses startActivityForResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN) return
        val m = pendingBarcode ?: return
        val code = data?.getStringExtra("code")
        if (resultCode != RESULT_OK || code.isNullOrEmpty()) return
        m.code = code
        val nameEdit = EditText(this).apply {
            hint = "e.g. Toothpaste in the bathroom"
            setText(m.note)
        }
        val wrap = vertical(20).apply { addView(nameEdit) }
        AlertDialog.Builder(this)
            .setTitle("Barcode registered ✓")
            .setMessage("Name it so you know where to go in the morning:")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                m.note = nameEdit.text.toString().trim()
                commitMission(m, pendingIsNew)
            }
            .setCancelable(false)
            .show()
    }

    private fun preview() {
        store.preview = alarm.deepCopy().apply { id = Store.PREVIEW_ID }
        store.snoozesUsed = 0
        AlarmService.start(this, Store.PREVIEW_ID, Scheduler.MODE_ALARM)
    }

    private fun save() {
        alarm.enabled = true
        store.save(alarm)
        Scheduler.schedule(this, alarm)
        val ms = alarm.nextTrigger() - System.currentTimeMillis()
        Toast.makeText(this, "Alarm set for ${untilText(ms)} from now", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this alarm?")
            .setPositiveButton("Delete") { _, _ ->
                Scheduler.cancel(this, alarm.id)
                store.delete(alarm.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
