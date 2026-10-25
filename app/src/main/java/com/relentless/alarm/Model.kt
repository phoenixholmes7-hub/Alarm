package com.relentless.alarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object MissionType {
    const val MATH = "math"
    const val MEMORY = "memory"
    const val TYPING = "typing"
    const val SHAKE = "shake"
    const val STEPS = "steps"
    const val BARCODE = "barcode"

    val ALL = listOf(MATH, MEMORY, TYPING, SHAKE, STEPS, BARCODE)

    fun name(t: String) = when (t) {
        MATH -> "Math"
        MEMORY -> "Memory"
        TYPING -> "Typing"
        SHAKE -> "Shake"
        STEPS -> "Steps"
        BARCODE -> "Find the item"
        else -> t
    }

    fun icon(t: String) = when (t) {
        MATH -> "➗"
        MEMORY -> "🧠"
        TYPING -> "⌨️"
        SHAKE -> "📳"
        STEPS -> "🚶"
        BARCODE -> "🔍"
        else -> "⏰"
    }

    fun blurb(t: String) = when (t) {
        MATH -> "Solve math problems"
        MEMORY -> "Remember and tap a tile pattern"
        TYPING -> "Type motivational phrases exactly"
        SHAKE -> "Shake the phone hard"
        STEPS -> "Get up and walk"
        BARCODE -> "Get up and scan an item in another room"
        else -> ""
    }

    fun unit(t: String) = when (t) {
        MATH -> "problems"
        MEMORY -> "rounds"
        TYPING -> "phrases"
        SHAKE -> "shakes"
        STEPS -> "steps"
        else -> ""
    }

    fun countOptions(t: String): IntArray = when (t) {
        MATH -> intArrayOf(1, 2, 3, 4, 5, 6, 8, 10)
        MEMORY -> intArrayOf(1, 2, 3, 4, 5)
        TYPING -> intArrayOf(1, 2, 3, 4, 5)
        SHAKE -> intArrayOf(20, 30, 50, 80, 120, 200)
        STEPS -> intArrayOf(20, 40, 60, 100, 150, 250)
        else -> intArrayOf(1)
    }

    fun defaultCount(t: String) = when (t) {
        MATH -> 3
        MEMORY -> 3
        TYPING -> 2
        SHAKE -> 50
        STEPS -> 40
        else -> 1
    }

    fun hasLevel(t: String) = t == MATH || t == MEMORY

    fun levelNames(t: String): Array<String> = when (t) {
        MATH -> arrayOf(
            "Very easy  (23 + 7)",
            "Easy  (34 + 58)",
            "Medium  (17 × 6 + 42)",
            "Hard  (24 × 17 + 63)",
            "Very hard  (38 × 27 + 412)",
        )
        MEMORY -> arrayOf(
            "Very easy  (3×3, 3 tiles)",
            "Easy  (3×3, 4 tiles)",
            "Medium  (4×4, 5 tiles)",
            "Hard  (4×4, 7 tiles)",
            "Very hard  (5×5, 9 tiles)",
        )
        else -> arrayOf("Normal")
    }

    val LEVEL_SHORT = arrayOf("Very easy", "Easy", "Medium", "Hard", "Very hard")
}

data class Mission(
    var type: String,
    var level: Int = 2,
    var count: Int = MissionType.defaultCount(type),
    var code: String = "",
    var note: String = "",
) {
    fun describe(): String = when (type) {
        MissionType.BARCODE ->
            if (code.isEmpty()) "Find the item (no item registered!)" else "Find: ${note.ifBlank { "your item" }}"
        else -> buildString {
            append("${MissionType.name(type)} · $count ${MissionType.unit(type)}")
            if (MissionType.hasLevel(type)) append(" · ${MissionType.LEVEL_SHORT[level.coerceIn(0, 4)]}")
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("level", level).put("count", count).put("code", code).put("note", note)

    companion object {
        fun from(o: JSONObject) = Mission(
            o.optString("type", MissionType.MATH),
            o.optInt("level", 2),
            o.optInt("count", 3),
            o.optString("code", ""),
            o.optString("note", ""),
        )
    }
}

data class Alarm(
    var id: Int,
    var hour: Int = 7,
    var minute: Int = 0,
    /** Bit 0 = Monday … bit 6 = Sunday. 0 = rings once. */
    var days: Int = 0,
    var enabled: Boolean = true,
    var label: String = "",
    var missions: MutableList<Mission> = mutableListOf(Mission(MissionType.MATH)),
    var maxSnoozes: Int = 1,
    var snoozeMin: Int = 5,
    var wakeCheck: Boolean = true,
) {
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (days != 0) {
            repeat(8) {
                if (days and (1 shl dayBit(cal.get(Calendar.DAY_OF_WEEK))) != 0) return cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    fun daysText(): String = when (days) {
        0 -> "Once"
        0x7F -> "Every day"
        0x1F -> "Weekdays"
        0x60 -> "Weekends"
        else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { DAY_SHORT[it] }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("hour", hour).put("minute", minute).put("days", days)
        .put("enabled", enabled).put("label", label)
        .put("missions", JSONArray().apply { missions.forEach { put(it.toJson()) } })
        .put("maxSnoozes", maxSnoozes).put("snoozeMin", snoozeMin).put("wakeCheck", wakeCheck)

    fun deepCopy(): Alarm = from(toJson())

    companion object {
        val DAY_SHORT = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** Calendar.DAY_OF_WEEK (Sunday = 1) -> our bit index (Monday = 0). */
        fun dayBit(calDay: Int) = (calDay + 5) % 7

        fun from(o: JSONObject): Alarm {
            val ms = mutableListOf<Mission>()
            o.optJSONArray("missions")?.let { arr ->
                for (i in 0 until arr.length()) ms.add(Mission.from(arr.getJSONObject(i)))
            }
            return Alarm(
                id = o.getInt("id"),
                hour = o.optInt("hour", 7),
                minute = o.optInt("minute", 0),
                days = o.optInt("days", 0),
                enabled = o.optBoolean("enabled", true),
                label = o.optString("label", ""),
                missions = ms,
                maxSnoozes = o.optInt("maxSnoozes", 1),
                snoozeMin = o.optInt("snoozeMin", 5),
                wakeCheck = o.optBoolean("wakeCheck", true),
            )
        }
    }
}

/**
 * All persistent state. Lives in device-protected storage so alarms still
 * work after a reboot before the phone has been unlocked.
 */
class Store(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("relentless", Context.MODE_PRIVATE)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).commit()
    }

    // ---- alarms ----

    fun alarms(): MutableList<Alarm> {
        val arr = try {
            JSONArray(p.getString("alarms", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return MutableList(arr.length()) { Alarm.from(arr.getJSONObject(it)) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
            .toMutableList()
    }

    private fun saveAll(list: List<Alarm>) = edit {
        putString("alarms", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    fun alarm(id: Int): Alarm? = if (id == PREVIEW_ID) preview else alarms().find { it.id == id }

    fun save(alarm: Alarm) {
        val list = alarms()
        val i = list.indexOfFirst { it.id == alarm.id }
        if (i >= 0) list[i] = alarm else list.add(alarm)
        saveAll(list)
    }

    fun delete(id: Int) = saveAll(alarms().filter { it.id != id })

    fun newId(): Int = (alarms().maxOfOrNull { it.id } ?: 0) + 1

    /** Alarm used by "Preview" and "Test" (id 0, never stored in the list). */
    var preview: Alarm?
        get() = p.getString("preview", null)?.let {
            try {
                Alarm.from(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
        set(v) = edit { putString("preview", v?.toJson()?.toString()) }

    // ---- global settings ----

    var sound: String
        get() = p.getString("sound", SOUND_SIREN) ?: SOUND_SIREN
        set(v) = edit { putString("sound", v) }

    var soundName: String
        get() = p.getString("soundName", "Two-tone siren") ?: "Two-tone siren"
        set(v) = edit { putString("soundName", v) }

    /** Watch plays a siren through its speaker as well as vibrating. */
    var watchSound: Boolean
        get() = p.getBoolean("watchSound", true)
        set(v) = edit { putBoolean("watchSound", v) }

    var flashlight: Boolean
        get() = p.getBoolean("flashlight", true)
        set(v) = edit { putBoolean("flashlight", v) }

    var checkDelayMin: Int
        get() = p.getInt("checkDelayMin", 5)
        set(v) = edit { putInt("checkDelayMin", v) }

    var checkWindowSec: Int
        get() = p.getInt("checkWindowSec", 90)
        set(v) = edit { putInt("checkWindowSec", v) }

    /** Copy alarms set in Google Clock (e.g. by Gemini) into Wake Up Holmes. */
    var followClock: Boolean
        get() = p.getBoolean("followClock", false)
        set(v) = edit { putBoolean("followClock", v) }

    /** Missions etc. for alarms created by voice / other apps: copied from your first alarm. */
    fun newAlarmLike(hour: Int, minute: Int, days: Int, label: String): Alarm {
        val t = alarms().firstOrNull()
        return Alarm(
            id = newId(),
            hour = hour,
            minute = minute,
            days = days,
            enabled = true,
            label = label,
            missions = t?.missions?.map { it.copy() }?.toMutableList() ?: mutableListOf(Mission(MissionType.MATH)),
            maxSnoozes = t?.maxSnoozes ?: 1,
            snoozeMin = t?.snoozeMin ?: 5,
            wakeCheck = t?.wakeCheck ?: true,
        )
    }

    // ---- live ringing state ----

    var snoozesUsed: Int
        get() = p.getInt("snoozesUsed", 0)
        set(v) = edit { putInt("snoozesUsed", v) }

    val ringing get() = p.getBoolean("ringing", false)
    val ringingId get() = p.getInt("ringingId", PREVIEW_ID)
    val ringingMode get() = p.getString("ringingMode", Scheduler.MODE_ALARM) ?: Scheduler.MODE_ALARM

    fun setRinging(id: Int, mode: String) = edit {
        putBoolean("ringing", true); putInt("ringingId", id); putString("ringingMode", mode)
    }

    fun clearRinging() = edit { putBoolean("ringing", false) }

    // ---- the single "extra" slot: snooze / wake-up check / watchdog / test ----

    val extraTime get() = p.getLong("extraTime", 0L)
    val extraId get() = p.getInt("extraId", PREVIEW_ID)
    val extraMode get() = p.getString("extraMode", Scheduler.MODE_ALARM) ?: Scheduler.MODE_ALARM

    fun setExtra(time: Long, id: Int, mode: String) = edit {
        putLong("extraTime", time); putInt("extraId", id); putString("extraMode", mode)
    }

    fun clearExtra() = edit { putLong("extraTime", 0L) }

    companion object {
        /** Built-in sounds are stored as "siren" (two-tone) or "siren:<style>". Anything else is a ringtone URI. */
        const val SOUND_SIREN = "siren"

        fun isBuiltIn(sound: String) = sound == SOUND_SIREN || sound.startsWith("$SOUND_SIREN:")

        fun sirenStyle(sound: String) = sound.substringAfter(':', Siren.TWO_TONE).ifBlank { Siren.TWO_TONE }

        fun builtInSound(style: String) = if (style == Siren.TWO_TONE) SOUND_SIREN else "$SOUND_SIREN:$style"
        const val PREVIEW_ID = 0
    }
}
