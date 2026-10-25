package com.relentless.alarm.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/** Opened from the watch's app list: shows whether the phone is connected, plus a test button. */
class WatchHomeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchAlarm.ensureChannel(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val pad = (resources.displayMetrics.widthPixels * 0.12).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        col.addView(TextView(this).apply {
            text = "WAKE UP"
            textSize = 11f
            letterSpacing = 0.2f
            setTextColor(0xFF8B919C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "Holmes"
            textSize = 26f
            typeface = resources.getFont(R.font.holmes)
            setTextColor(0xFFFF6B4A.toInt())
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            text = "Looking for your phone…"
            textSize = 13f
            setTextColor(0xFF8B919C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "Test buzz"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(0xFFFF6B4A.toInt())
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener {
                WatchAlarm.ring(this@WatchHomeActivity, JSONObject().put("label", "Test").toString(), test = true)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        col.addView(TextView(this).apply {
            text = "When your phone alarm rings, this watch buzzes until you finish the mission on the phone."
            textSize = 11f
            setTextColor(0xFF8B919C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Pull the latest "next alarm" from the phone in case the tile missed an update.
        try {
            Wearable.getDataClient(this).getDataItems(android.net.Uri.parse("wear://*/holmes/next"))
                .addOnSuccessListener { items ->
                    for (item in items) {
                        val map = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).dataMap
                        NextAlarm.save(this, map.getLong("time"), map.getString("label").orEmpty(), map.getString("missions").orEmpty())
                    }
                    items.release()
                    HolmesTileService.refresh(this)
                }
        } catch (_: Exception) {
        }
        try {
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes ->
                    status.text = if (nodes.isEmpty()) "Phone not connected" else "✓ Connected to ${nodes.first().displayName}"
                    status.setTextColor(if (nodes.isEmpty()) 0xFFFBBF24.toInt() else 0xFF34D399.toInt())
                }
                .addOnFailureListener { status.text = "Phone not connected" }
        } catch (_: Exception) {
            status.text = "Phone not connected"
        }
    }
}
