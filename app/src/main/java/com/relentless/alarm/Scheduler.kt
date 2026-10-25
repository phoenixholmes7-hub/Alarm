package com.relentless.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Everything goes through AlarmManager.setAlarmClock(): it is the most reliable
 * API on Android — exempt from Doze and battery savers, and allowed to start
 * the ringing service from the background.
 */
object Scheduler {
    const val ACTION_FIRE = "com.relentless.alarm.action.FIRE"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_MODE = "mode"
    const val EXTRA_IS_EXTRA = "is_extra"
    const val MODE_ALARM = "alarm"
    const val MODE_CHECK = "check"

    /** Request code for the single snooze / wake-check / watchdog slot. Alarm ids stay below it. */
    private const val EXTRA_REQ = 1_000_000

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    private fun firePi(ctx: Context, id: Int, mode: String, req: Int, isExtra: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM_ID, id)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_IS_EXTRA, isExtra)
        return PendingIntent.getBroadcast(
            ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setClock(ctx: Context, time: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(time, show), pi)
        } catch (e: SecurityException) {
            // Exact alarm permission missing (Android 12 only) — inexact is better than nothing.
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }

    fun schedule(ctx: Context, alarm: Alarm, syncWatch: Boolean = true) {
        val pi = firePi(ctx, alarm.id, MODE_ALARM, alarm.id, false)
        if (alarm.enabled) setClock(ctx, alarm.nextTrigger(), pi) else am(ctx).cancel(pi)
        if (syncWatch) WatchBridge.syncNextAlarm(ctx)
    }

    fun cancel(ctx: Context, id: Int) {
        am(ctx).cancel(firePi(ctx, id, MODE_ALARM, id, false))
        WatchBridge.syncNextAlarm(ctx)
    }

    fun scheduleExtra(ctx: Context, id: Int, mode: String, time: Long) {
        Store(ctx).setExtra(time, id, mode)
        setClock(ctx, time, firePi(ctx, id, mode, EXTRA_REQ, true))
    }

    /** While ringing, keep re-arming this; if the app is ever killed mid-alarm it rings again. */
    fun scheduleWatchdog(ctx: Context, id: Int) =
        scheduleExtra(ctx, id, MODE_ALARM, System.currentTimeMillis() + 120_000L)

    fun cancelExtra(ctx: Context) {
        Store(ctx).clearExtra()
        am(ctx).cancel(firePi(ctx, 0, MODE_ALARM, EXTRA_REQ, true))
    }

    fun rescheduleAll(ctx: Context) {
        val store = Store(ctx)
        store.alarms().forEach { schedule(ctx, it, syncWatch = false) }
        WatchBridge.syncNextAlarm(ctx)
        val now = System.currentTimeMillis()
        val t = store.extraTime
        if (t > 0) {
            setClock(ctx, maxOf(t, now + 3000), firePi(ctx, store.extraId, store.extraMode, EXTRA_REQ, true))
        } else if (store.ringing && !AlarmService.isRinging) {
            // Phone rebooted / app died while an alarm was ringing: ring again.
            scheduleExtra(ctx, store.ringingId, MODE_ALARM, now + 3000)
        }
    }

    fun nextAlarm(ctx: Context): Pair<Alarm, Long>? =
        Store(ctx).alarms().filter { it.enabled }.map { it to it.nextTrigger() }.minByOrNull { it.second }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val store = Store(context)
        val id = intent.getIntExtra(Scheduler.EXTRA_ALARM_ID, Store.PREVIEW_ID)
        val mode = intent.getStringExtra(Scheduler.EXTRA_MODE) ?: Scheduler.MODE_ALARM
        if (intent.getBooleanExtra(Scheduler.EXTRA_IS_EXTRA, false)) {
            store.clearExtra()
        } else {
            val alarm = store.alarm(id) ?: return
            if (!alarm.enabled) return
            if (alarm.days == 0) {
                alarm.enabled = false
                store.save(alarm)
                WatchBridge.syncNextAlarm(context)
            } else {
                Scheduler.schedule(context, alarm)
            }
            store.snoozesUsed = 0
        }
        AlarmService.start(context, id, mode)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}
