package com.relentless.alarm.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/** Ringing state on the watch. The phone re-sends "ring" every 20 s while its alarm is going. */
object WatchAlarm {
    const val RING = "/holmes/ring"
    const val STOP = "/holmes/stop"
    const val TEST = "/holmes/test"

    private const val CHANNEL = "watch_alarm"
    private const val NOTIF_ID = 77

    /** If the phone goes quiet for this long (out of range, battery died) the watch stops on its own. */
    const val ALARM_TIMEOUT_MS = 90_000L
    const val TEST_TIMEOUT_MS = 6_000L

    @Volatile var lastRing = 0L
        private set
    @Volatile var isTest = false
        private set
    @Volatile var label = "Alarm"
        private set
    @Volatile var missions = ""
        private set
    @Volatile var mode = "alarm"
        private set

    val active: Boolean
        get() = lastRing > 0 &&
            SystemClock.elapsedRealtime() - lastRing < if (isTest) TEST_TIMEOUT_MS else ALARM_TIMEOUT_MS

    fun ring(ctx: Context, payload: String, test: Boolean) {
        try {
            val o = JSONObject(payload)
            label = o.optString("label", "Alarm")
            missions = o.optString("missions", "")
            mode = o.optString("mode", "alarm")
        } catch (_: Exception) {
        }
        isTest = test
        lastRing = SystemClock.elapsedRealtime()

        val open = WatchAlarmActivity.instance
        if (open != null) {
            open.refresh()
            return
        }
        // A full-screen notification is the reliable way to show the alarm from the background;
        // starting the activity directly also works on many watches.
        postNotification(ctx)
        try {
            ctx.startActivity(
                Intent(ctx, WatchAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("WatchAlarm", "direct launch blocked; relying on full-screen notification", e)
        }
    }

    fun stop(ctx: Context) {
        lastRing = 0L
        WatchAlarmActivity.instance?.finish()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarm on watch", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Buzzes your wrist while the phone alarm rings"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 300, 1000, 300, 1000)
                setSound(
                    null,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
    }

    private fun postNotification(ctx: Context) {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, WatchAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(if (isTest) "Wake Up Holmes test" else "Wake up, Holmes!")
            .setContentText("Do your mission on your phone")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setLocalOnly(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.w("WatchAlarm", "notification permission missing", e)
        }
    }
}

class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != "/holmes/next") continue
            val map = DataMapItem.fromDataItem(e.dataItem).dataMap
            NextAlarm.save(this, map.getLong("time"), map.getString("label").orEmpty(), map.getString("missions").orEmpty())
        }
        HolmesTileService.refresh(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data)
        when (event.path) {
            WatchAlarm.RING -> WatchAlarm.ring(this, payload, test = false)
            WatchAlarm.TEST -> WatchAlarm.ring(this, payload, test = true)
            WatchAlarm.STOP -> WatchAlarm.stop(this)
        }
    }
}
