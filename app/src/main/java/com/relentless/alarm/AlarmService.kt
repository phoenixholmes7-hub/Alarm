package com.relentless.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

private val ALARM_ATTRS: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/** A harsh two-tone square-wave siren, generated in code so it always works (even before first unlock). */
class Siren {
    private var track: AudioTrack? = null

    fun start(volume: Float): Boolean {
        if (track != null) return true
        return try {
            val rate = 44100
            val seg = rate / 5
            val total = seg * 10
            val data = ShortArray(total)
            for (i in 0 until total) {
                val f = if ((i / seg) % 2 == 0) 880.0 else 1320.0
                val phase = (i * f / rate) % 1.0
                data[i] = (if (phase < 0.5) 30000 else -30000).toShort()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(ALARM_ATTRS)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(total * 2)
                .build()
            t.write(data, 0, total)
            t.setLoopPoints(0, total, -1)
            t.setVolume(volume)
            t.play()
            track = t
            true
        } catch (e: Exception) {
            Log.e("Siren", "failed", e)
            stop()
            false
        }
    }

    fun setVolume(v: Float) {
        try {
            track?.setVolume(v)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }
}

class AlarmService : Service() {
    companion object {
        const val ACTION_START = "com.relentless.alarm.action.START"
        const val ACTION_DISMISS = "com.relentless.alarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.relentless.alarm.action.SNOOZE"
        private const val CHANNEL = "ringing"
        private const val NOTIF_ID = 4242

        @Volatile var isRinging = false
            private set
        @Volatile var currentMode = Scheduler.MODE_ALARM
            private set
        @Volatile var currentAlarmId = Store.PREVIEW_ID
            private set
        /** Wall-clock time the current ring started. */
        @Volatile var ringStartedAt = 0L
            private set
        /** Set by the alarm screen while a mission is in progress. */
        @Volatile var missionActive = false
        @Volatile private var lastUserActivity = 0L
        @Volatile private var instance: AlarmService? = null

        /** Called on every touch during a mission: keeps the sound quiet while you're working on it. */
        fun userActive() {
            lastUserActivity = SystemClock.elapsedRealtime()
        }

        fun start(ctx: Context, alarmId: Int, mode: String) {
            val i = Intent(ctx, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(Scheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(Scheduler.EXTRA_MODE, mode)
            ctx.startForegroundService(i)
        }

        fun dismiss(ctx: Context) {
            instance?.dismiss() ?: command(ctx, ACTION_DISMISS)
        }

        fun snooze(ctx: Context) {
            instance?.snooze() ?: command(ctx, ACTION_SNOOZE)
        }

        private fun command(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w("AlarmService", "command $action failed", e)
            }
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            val ch = NotificationChannel(CHANNEL, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows the alarm screen when an alarm goes off"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private val siren = Siren()
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchId: String? = null
    private var torchOn = false
    private var tick = 0
    private var appliedVolume = -1f
    private var escalated = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> {
                // Restarted by the system after being killed.
                if (store.ringing) {
                    startRinging(store.ringingId, store.ringingMode)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_START -> {
                val id = intent.getIntExtra(Scheduler.EXTRA_ALARM_ID, Store.PREVIEW_ID)
                val mode = intent.getStringExtra(Scheduler.EXTRA_MODE) ?: Scheduler.MODE_ALARM
                if (isRinging && currentMode == Scheduler.MODE_ALARM && mode == Scheduler.MODE_CHECK) {
                    // Already ringing for real; don't downgrade to a wake-up check.
                    startForegroundCompat(buildNotification())
                } else {
                    startRinging(id, mode)
                }
            }
            ACTION_DISMISS -> dismiss()
            ACTION_SNOOZE -> snooze()
        }
        return START_STICKY
    }

    private fun startRinging(id: Int, mode: String) {
        isRinging = true
        currentAlarmId = id
        currentMode = mode
        missionActive = false
        ringStartedAt = System.currentTimeMillis()
        store.setRinging(id, mode)

        ensureChannel(this)
        startForegroundCompat(buildNotification())
        acquireWakeLock()

        handler.removeCallbacksAndMessages(null)
        stopOutputs()
        forceStreamVolume()
        startSound()
        startVibration()
        if (mode == Scheduler.MODE_ALARM && store.flashlight) startStrobe()
        if (mode == Scheduler.MODE_CHECK) {
            handler.postDelayed({ startRinging(id, Scheduler.MODE_ALARM) }, store.checkWindowSec * 1000L)
        }
        tick = 0
        handler.post(ticker)
        launchActivity()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isRinging) return
            tick++
            forceStreamVolume()
            applyVolume()
            if (currentMode == Scheduler.MODE_ALARM && !escalated &&
                store.sound != Store.SOUND_SIREN && System.currentTimeMillis() - ringStartedAt > 45_000
            ) {
                // Still not up after 45s with a normal ringtone: add the siren on top.
                escalated = true
                siren.start(appliedVolume.coerceAtLeast(0.1f))
            }
            if (tick % 4 == 0 && !AlarmActivity.visible) launchActivity()
            if (tick % 60 == 1) {
                Scheduler.scheduleWatchdog(this@AlarmService, currentAlarmId)
                acquireWakeLock()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun targetVolume(): Float = when {
        currentMode == Scheduler.MODE_CHECK -> 0.4f
        missionActive && SystemClock.elapsedRealtime() - lastUserActivity < 12_000 -> 0.12f
        else -> 1f
    }

    private fun applyVolume() {
        val v = targetVolume()
        if (v == appliedVolume) return
        appliedVolume = v
        try {
            player?.setVolume(v, v)
        } catch (_: Exception) {
        }
        siren.setVolume(v)
    }

    /** Pins the system alarm volume to max every second, so the volume buttons can't save you. */
    private fun forceStreamVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: Exception) {
            Log.w("AlarmService", "volume", e)
        }
    }

    private fun startSound() {
        val s = store.sound
        if (s != Store.SOUND_SIREN) {
            val uris = listOfNotNull(
                runCatching { Uri.parse(s) }.getOrNull(),
                runCatching { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) }.getOrNull(),
            )
            for (u in uris) if (startPlayer(u)) {
                applyVolume()
                return
            }
        }
        if (siren.start(1f)) {
            applyVolume()
            return
        }
        try {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "no sound at all", e)
        }
    }

    private fun startPlayer(uri: Uri): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(ALARM_ATTRS)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            mp.release()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        vibrator = v
        try {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 400), 0), ALARM_ATTRS)
        } catch (e: Exception) {
            Log.w("AlarmService", "vibrate", e)
        }
    }

    private val strobe = object : Runnable {
        override fun run() {
            torchOn = !torchOn
            setTorch(torchOn)
            handler.postDelayed(this, 250)
        }
    }

    private fun startStrobe() {
        try {
            val cm = getSystemService(CameraManager::class.java)
            torchId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            torchId = null
        }
        if (torchId != null) handler.post(strobe)
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        try {
            getSystemService(CameraManager::class.java).setTorchMode(id, on)
        } catch (_: Exception) {
        }
    }

    private fun stopOutputs() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        siren.stop()
        tone?.let {
            it.stopTone()
            it.release()
        }
        tone = null
        vibrator?.cancel()
        vibrator = null
        handler.removeCallbacks(strobe)
        setTorch(false)
        torchOn = false
        escalated = false
        appliedVolume = -1f
    }

    private fun launchActivity() {
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        } catch (e: Exception) {
            Log.w("AlarmService", "launch", e)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val check = currentMode == Scheduler.MODE_CHECK
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(if (check) "Wake-up check — are you awake?" else "Alarm! Time to get up")
            .setContentText(if (check) "Tap to confirm or the alarm starts again" else "Complete your mission to turn it off")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // The watchdog alarm will bring us back if this ever fails.
            Log.e("AlarmService", "startForeground", e)
        }
    }

    private fun acquireWakeLock() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relentless:ring")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        wl.acquire(10 * 60 * 1000L)
    }

    fun dismiss() {
        val id = currentAlarmId
        val wasAlarm = currentMode == Scheduler.MODE_ALARM
        stopRinging {
            val a = store.alarm(id)
            if (wasAlarm && id != Store.PREVIEW_ID && a?.wakeCheck == true) {
                Scheduler.scheduleExtra(
                    this, id, Scheduler.MODE_CHECK,
                    System.currentTimeMillis() + store.checkDelayMin * 60_000L
                )
            }
        }
    }

    fun snooze() {
        if (!isRinging || currentMode != Scheduler.MODE_ALARM) return
        val id = currentAlarmId
        val a = store.alarm(id)
        if (store.snoozesUsed >= (a?.maxSnoozes ?: 0)) return
        store.snoozesUsed = store.snoozesUsed + 1
        stopRinging {
            Scheduler.scheduleExtra(
                this, id, Scheduler.MODE_ALARM,
                System.currentTimeMillis() + (a?.snoozeMin ?: 5) * 60_000L
            )
        }
    }

    private fun stopRinging(after: () -> Unit) {
        val was = isRinging
        isRinging = false
        missionActive = false
        handler.removeCallbacksAndMessages(null)
        stopOutputs()
        if (was) {
            store.clearRinging()
            Scheduler.cancelExtra(this)
            after()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopOutputs()
        wakeLock?.let { if (it.isHeld) it.release() }
        // If we're destroyed while still ringing, the watchdog alarm re-fires within 2 minutes.
        isRinging = false
        if (instance === this) instance = null
        super.onDestroy()
    }
}
