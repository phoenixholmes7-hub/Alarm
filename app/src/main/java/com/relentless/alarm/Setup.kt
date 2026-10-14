package com.relentless.alarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Phone settings that can silently stop an alarm, and how to fix each one. */
object Setup {
    class Check(val title: String, val why: String, val ok: Boolean, val fix: (Activity) -> Unit)

    fun checks(a: Activity): List<Check> {
        val nm = a.getSystemService(NotificationManager::class.java)
        val pm = a.getSystemService(PowerManager::class.java)
        val pkg = a.packageName
        val list = mutableListOf<Check>()
        list += Check("Notifications", "Needed to show the alarm over the lock screen.", nm.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                it.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            }
        }
        list += Check("Exact alarms", "Lets the alarm go off at the exact minute.", Scheduler.canScheduleExact(a)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            list += Check("Full-screen alarm", "Lets the alarm take over the screen when the phone is locked.", nm.canUseFullScreenIntent()) {
                open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            }
        }
        list += Check("Unrestricted battery", "Stops Android from killing the alarm to save battery.", pm.isIgnoringBatteryOptimizations(pkg)) {
            open(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
        }
        list += Check("Display over other apps", "Brings the alarm screen back if you try to leave it.", Settings.canDrawOverlays(a)) {
            open(it, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))
        }
        return list
    }

    private fun open(a: Activity, i: Intent) {
        try {
            a.startActivity(i)
        } catch (e: Exception) {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        }
    }
}
