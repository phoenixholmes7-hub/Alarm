package com.relentless.alarm

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Talks to the Wake Up Holmes watch app over the Wear OS Data Layer.
 * Both apps share the same application id and signing key, which is what lets them pair.
 */
object WatchBridge {
    const val RING = "/holmes/ring"
    const val STOP = "/holmes/stop"
    const val TEST = "/holmes/test"

    fun send(ctx: Context, path: String, payload: String = "") {
        try {
            val app = ctx.applicationContext
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                for (n in nodes) {
                    Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray())
                }
            }
        } catch (e: Exception) {
            // No Play services (or not unlocked yet after a reboot): the phone still rings.
            Log.w("WatchBridge", "send $path failed", e)
        }
    }

    /** Publishes the next alarm to the watch tile (Data Layer: synced whenever the watch connects). */
    fun syncNextAlarm(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val next = Scheduler.nextAlarm(app)
            val req = PutDataMapRequest.create("/holmes/next").apply {
                dataMap.putLong("time", next?.second ?: 0L)
                dataMap.putString("label", next?.first?.label.orEmpty())
                dataMap.putString(
                    "missions",
                    next?.first?.missions?.joinToString(" ") { MissionType.icon(it.type) }.orEmpty()
                )
            }
            Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent())
        } catch (e: Exception) {
            Log.w("WatchBridge", "sync failed", e)
        }
    }

    /** Names of connected watches, or null if the Wear OS service isn't available. */
    fun connectedWatches(ctx: Context, callback: (List<String>?) -> Unit) {
        try {
            Wearable.getNodeClient(ctx.applicationContext).connectedNodes
                .addOnSuccessListener { nodes -> callback(nodes.map { it.displayName }) }
                .addOnFailureListener { callback(null) }
        } catch (e: Exception) {
            callback(null)
        }
    }
}
