package com.relentless.alarm

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Uninstall protection. While the app is an active device admin, Android refuses to uninstall it.
 * Turning protection off from Android's settings locks the phone and sets off the full alarm;
 * the clean way out is the in-app gauntlet (hard math + typing), done while actually awake.
 */
object Guard {
    fun component(ctx: Context) = ComponentName(ctx, GuardReceiver::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isOn(ctx: Context): Boolean = try {
        dpm(ctx).isAdminActive(component(ctx))
    } catch (e: Exception) {
        false
    }

    fun enableIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Stops you from uninstalling Wake Up Holmes half-asleep. To remove it later, use " +
                "Settings › Uninstall protection › Turn off inside the app (you'll have to prove you're awake)."
        )

    /** The clean way out: a silent run of hard missions. Finishing it removes protection. */
    fun startGauntlet(ctx: Context) {
        val store = Store(ctx)
        store.preview = Alarm(
            id = Store.PREVIEW_ID,
            label = "Turn off protection",
            missions = mutableListOf(
                Mission(MissionType.MATH, level = 4, count = 10),
                Mission(MissionType.TYPING, count = 3),
            ),
            maxSnoozes = 0,
            wakeCheck = false,
        )
        store.snoozesUsed = 0
        AlarmService.start(ctx, Store.PREVIEW_ID, Scheduler.MODE_ALARM, silent = true, gauntlet = true)
    }

    /** Called when the gauntlet is completed. */
    fun removeProtection(ctx: Context) {
        try {
            dpm(ctx).removeActiveAdmin(component(ctx))
            Toast.makeText(ctx, "Uninstall protection is off. You can uninstall now.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w("Guard", "removeActiveAdmin failed", e)
        }
    }

    /** Someone tried to switch protection off in Android's settings: full alarm, no snoozes. */
    fun tamperAlarm(ctx: Context) {
        val store = Store(ctx)
        val source = store.alarms().firstOrNull { it.enabled } ?: store.alarms().firstOrNull()
        val missions = source?.missions?.map { it.copy() }?.toMutableList()?.takeIf { it.isNotEmpty() }
            ?: mutableListOf(Mission(MissionType.MATH, level = 3, count = 5))
        store.preview = Alarm(
            id = Store.PREVIEW_ID,
            label = "Nice try, Holmes",
            missions = missions,
            maxSnoozes = 0,
            wakeCheck = false,
        )
        store.snoozesUsed = 0
        // Fired through AlarmManager so the ringing service is allowed to start from the background.
        Scheduler.scheduleExtra(ctx, Store.PREVIEW_ID, Scheduler.MODE_ALARM, System.currentTimeMillis() + 1000)
    }

    fun lockNow(ctx: Context) {
        try {
            dpm(ctx).lockNow()
        } catch (e: Exception) {
            Log.w("Guard", "lockNow failed", e)
        }
    }
}

class GuardReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Guard.tamperAlarm(context)
        Guard.lockNow(context)
        return "Nice try, Holmes. The alarm is going off and you'll have to finish your missions first. " +
            "To turn protection off properly, use Settings › Uninstall protection inside the app."
    }
}
