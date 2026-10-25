package com.relentless.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Harsh alarm sounds generated in code, so they always work (even before the phone is unlocked
 * after a reboot) and can't be "missing" like a ringtone file.
 */
class Siren {
    companion object {
        const val TWO_TONE = "twotone"
        const val KLAXON = "klaxon"
        const val WAIL = "wail"
        const val BEEPER = "beeper"

        val STYLES = listOf(TWO_TONE, KLAXON, WAIL, BEEPER)

        fun name(style: String) = when (style) {
            KLAXON -> "Klaxon"
            WAIL -> "Wail"
            BEEPER -> "Smoke-alarm beeper"
            else -> "Two-tone siren"
        }

        fun blurb(style: String) = when (style) {
            KLAXON -> "Harsh, buzzing horn blasts"
            WAIL -> "Rising and falling police wail"
            BEEPER -> "Rapid 3 kHz beeps, the most piercing pitch"
            else -> "Classic European two-tone"
        }

        private const val RATE = 44100
        private val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** One loop of the chosen sound, 16-bit mono. */
        fun generate(style: String): ShortArray {
            val out: ShortArray
            var phase = 0.0
            fun square(f: Double): Double {
                phase = (phase + f / RATE) % 1.0
                return if (phase < 0.5) 1.0 else -1.0
            }
            when (style) {
                KLAXON -> {
                    // Two detuned sawtooths (beating = extra harsh), hard-clipped, in short blasts.
                    val n = (RATE * 1.6).toInt()
                    out = ShortArray(n)
                    var p1 = 0.0
                    var p2 = 0.0
                    for (i in 0 until n) {
                        p1 = (p1 + 415.0 / RATE) % 1.0
                        p2 = (p2 + 440.0 / RATE) % 1.0
                        val t = (i % (RATE * 0.4)) / RATE
                        val on = t < 0.3
                        val v = ((p1 * 2 - 1) + (p2 * 2 - 1)) * 1.6
                        out[i] = if (on) (v.coerceIn(-1.0, 1.0) * 30000).toInt().toShort() else 0.toShort()
                    }
                }
                WAIL -> {
                    // Square wave sweeping 600 → 1700 → 600 Hz.
                    val n = (RATE * 2.4).toInt()
                    out = ShortArray(n)
                    for (i in 0 until n) {
                        val x = i.toDouble() / n
                        val tri = if (x < 0.5) x * 2 else (1 - x) * 2
                        out[i] = (square(600.0 + 1100.0 * tri) * 30000).toInt().toShort()
                    }
                }
                BEEPER -> {
                    // Four rapid 3.1 kHz beeps, then a short gap. Near-square for maximum loudness.
                    val n = (RATE * 0.85).toInt()
                    out = ShortArray(n)
                    for (i in 0 until n) {
                        val t = i.toDouble() / RATE
                        val inBurst = t < 0.6 && (t % 0.15) < 0.09
                        val s = sin(2 * PI * 3100.0 * t) * 1.8
                        out[i] = if (inBurst) (s.coerceIn(-1.0, 1.0) * 30000).toInt().toShort() else 0.toShort()
                    }
                }
                else -> {
                    // Two-tone: 880 / 1320 Hz square, switching every 0.2 s.
                    val seg = RATE / 5
                    val n = seg * 10
                    out = ShortArray(n)
                    for (i in 0 until n) {
                        val f = if ((i / seg) % 2 == 0) 880.0 else 1320.0
                        out[i] = (square(f) * 30000).toInt().toShort()
                    }
                }
            }
            return out
        }
    }

    private var track: AudioTrack? = null

    fun start(volume: Float, style: String = TWO_TONE): Boolean {
        if (track != null) return true
        return try {
            val data = generate(style)
            val t = AudioTrack.Builder()
                .setAudioAttributes(ATTRS)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            t.write(data, 0, data.size)
            t.setLoopPoints(0, data.size, -1)
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

    val isPlaying get() = track != null

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
