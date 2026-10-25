package com.relentless.alarm

import android.app.Activity
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.widget.Toast
import java.util.Calendar

/**
 * Handles Android's standard "set an alarm" request: what Gemini, Google Assistant and other apps
 * send to a clock app ("Hey Google, set an alarm for 7 AM"). The new alarm copies the missions,
 * snooze and wake-up check settings from your first alarm.
 */
class SetAlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            Log.e("SetAlarm", "failed", e)
            Toast.makeText(this, "Couldn't set that alarm", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun handle(i: Intent) {
        if (i.action == AlarmClock.ACTION_SHOW_ALARMS) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val hour = i.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        val minute = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
        if (hour !in 0..23 || minute !in 0..59) {
            // No time given: open the editor so it can be set by hand.
            startActivity(Intent(this, EditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        var days = 0
        i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.forEach { calDay ->
            if (calDay in Calendar.SUNDAY..Calendar.SATURDAY) days = days or (1 shl Alarm.dayBit(calDay))
        }
        val label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
        val alarm = VoiceAlarms.addOrEnable(this, hour, minute, days, label)
        val until = untilText(alarm.nextTrigger() - System.currentTimeMillis())
        Toast.makeText(this, "Wake Up Holmes: alarm set for ${fmtTime(hour, minute)}, in $until", Toast.LENGTH_LONG).show()
    }
}

object VoiceAlarms {
    /** Turns on a matching alarm if one exists, otherwise creates one. */
    fun addOrEnable(ctx: Context, hour: Int, minute: Int, days: Int, label: String): Alarm {
        val store = Store(ctx)
        val existing = store.alarms().firstOrNull { it.hour == hour && it.minute == minute && it.days == days }
        val alarm = existing?.apply {
            enabled = true
            if (label.isNotBlank()) this.label = label
        } ?: store.newAlarmLike(hour, minute, days, label)
        store.save(alarm)
        Scheduler.schedule(ctx, alarm)
        return alarm
    }
}

/**
 * "Follow Gemini / Google Clock alarms": when another app (usually Google Clock, which is where
 * Gemini puts alarms) sets the next alarm on the phone, add a matching Wake Up Holmes alarm.
 * Android only reports the single next alarm, so this covers alarms within the next 24 hours.
 */
class ClockFollowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) check(context)
    }

    companion object {
        fun check(context: Context) {
            val store = Store(context)
            if (!store.followClock) return
            val info = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return
            val creator = info.showIntent?.creatorPackage ?: return
            if (creator == context.packageName) return
            val now = System.currentTimeMillis()
            val t = info.triggerTime
            if (t <= now || t - now > 24 * 60 * 60 * 1000L) return
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            if (store.alarms().any { it.enabled && it.hour == h && it.minute == m }) return
            VoiceAlarms.addOrEnable(context, h, m, 0, "From Gemini")
        }
    }
}
