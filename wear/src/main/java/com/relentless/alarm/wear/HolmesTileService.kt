package com.relentless.alarm.wear

import android.content.Context
import android.text.format.DateFormat
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** The phone's next alarm, as last synced over the Data Layer. */
object NextAlarm {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("holmes_watch", Context.MODE_PRIVATE)

    fun save(ctx: Context, time: Long, label: String, missions: String) {
        prefs(ctx).edit().putLong("time", time).putString("label", label).putString("missions", missions).apply()
    }

    fun time(ctx: Context) = prefs(ctx).getLong("time", 0L)
    fun label(ctx: Context) = prefs(ctx).getString("label", "").orEmpty()
    fun missions(ctx: Context) = prefs(ctx).getString("missions", "").orEmpty()

    fun until(ms: Long): String {
        val total = ceil(ms / 60000.0).toLong().coerceAtLeast(1)
        val d = total / (60 * 24)
        val h = (total / 60) % 24
        val m = total % 60
        return when {
            d > 0 -> "in ${d}d ${h}h"
            h > 0 -> "in ${h}h ${m}m"
            else -> "in ${m}m"
        }
    }
}

class HolmesTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val tile = TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                // Re-render each minute so the countdown stays current.
                .setFreshnessIntervalMillis(60_000)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build()
            completer.set(tile)
            "holmes-tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion("1").build())
            "holmes-res"
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): LayoutElementBuilders.LayoutElement {
        val style = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(size))
            .setColor(argb(color))
        if (bold) style.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
        return LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setFontStyle(style.build())
            .setMaxLines(2)
            .build()
    }

    private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val t = NextAlarm.time(this)
        val now = System.currentTimeMillis()
        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("WAKE UP HOLMES", 11f, 0xFF8B919C.toInt(), bold = true))
            .addContent(gap(6f))
        if (t > now) {
            val day = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
            col.addContent(text(DateFormat.getTimeFormat(this).format(Date(t)), 34f, 0xFFFFFFFF.toInt()))
                .addContent(text("$day · ${NextAlarm.until(t - now)}", 14f, 0xFFFF6B4A.toInt(), bold = true))
            val label = NextAlarm.label(this)
            val missions = NextAlarm.missions(this)
            val extra = listOf(label, missions).filter { it.isNotBlank() }.joinToString("  ")
            if (extra.isNotBlank()) col.addContent(gap(4f)).addContent(text(extra, 13f, 0xFFCCCCCC.toInt()))
        } else {
            col.addContent(text("No alarm set", 20f, 0xFFFFFFFF.toInt()))
                .addContent(text("Set one on your phone", 13f, 0xFF8B919C.toInt()))
        }

        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WatchHomeActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(col.build())
            .build()
    }

    companion object {
        fun refresh(ctx: Context) {
            try {
                getUpdater(ctx).requestUpdate(HolmesTileService::class.java)
            } catch (_: Exception) {
            }
        }
    }
}
