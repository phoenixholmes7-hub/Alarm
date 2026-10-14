package com.relentless.alarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlin.math.min

/**
 * Camera barcode scanner used to register an item and to find it again in the morning.
 * Reads every common 1D/2D format, including inverted codes, with continuous autofocus and a torch.
 */
class ItemScanner(ctx: Context) : FrameLayout(ctx) {
    private val camera = BarcodeView(ctx)
    private val finder = Viewfinder(ctx)
    private val torchButton: TextView
    private var torchOn = false
    private var lastResult = ""
    private var lastResultAt = 0L

    /** Called with each distinct code the camera reads. */
    var onScan: ((String) -> Unit)? = null
    /** Called when the camera sees something barcode-like (the user is actively scanning). */
    var onActivity: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    init {
        background = ctx.rounded(Color.BLACK, 20)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        camera.setDecoderFactory(
            DefaultDecoderFactory(null, mapOf(DecodeHintType.TRY_HARDER to true), null, Intents.Scan.MIXED_SCAN)
        )
        camera.cameraSettings.setAutoFocusEnabled(true)
        camera.cameraSettings.setContinuousFocusEnabled(true)
        camera.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() {}
            override fun previewStarted() {}
            override fun previewStopped() {}
            override fun cameraClosed() {}
            override fun cameraError(error: Exception) {
                onError?.invoke(error)
            }
        })
        camera.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                val now = System.currentTimeMillis()
                // The decoder fires many times a second; report each code at most every 1.5 s.
                if (text == lastResult && now - lastResultAt < 1500) return
                lastResult = text
                lastResultAt = now
                onScan?.invoke(text)
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                if (!resultPoints.isNullOrEmpty()) onActivity?.invoke()
            }
        })

        addView(camera, LayoutParams(MATCH, MATCH))
        addView(finder, LayoutParams(MATCH, MATCH))

        torchButton = ctx.label("🔦", 20f, center = true).apply {
            background = ctx.rounded(0x99000000.toInt(), 24)
            setOnClickListener { setTorch(!torchOn) }
            contentDescription = "Flashlight"
        }
        addView(torchButton, LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(0, ctx.dp(12), ctx.dp(12), 0)
        })
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        try {
            camera.setTorch(on)
        } catch (_: Exception) {
        }
        torchButton.background = context.rounded(if (on) Colors.WARN else 0x99000000.toInt(), 24)
    }

    fun resume() {
        camera.resume()
    }

    fun pause() {
        torchOn = false
        camera.pause()
    }

    /** Green (found) or red (wrong item) flash of the viewfinder, with a haptic tap. */
    fun feedback(ok: Boolean) {
        finder.flash(if (ok) Colors.OK else Colors.ACCENT)
        val h = if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
        performHapticFeedback(h)
    }

    /** Corner brackets, dimmed surroundings and a sweeping scan line. */
    private class Viewfinder(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4 * density
            strokeCap = Paint.Cap.ROUND
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Colors.ACCENT
            strokeWidth = 2.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val shade = Paint().apply { color = 0x55000000 }
        private var progress = 0f
        private var tintUntil = 0L
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            anim.start()
        }

        override fun onDetachedFromWindow() {
            anim.cancel()
            super.onDetachedFromWindow()
        }

        fun flash(color: Int) {
            corner.color = color
            line.color = color
            tintUntil = System.currentTimeMillis() + 700
            invalidate()
            postDelayed({
                if (System.currentTimeMillis() >= tintUntil) {
                    corner.color = Color.WHITE
                    line.color = Colors.ACCENT
                    invalidate()
                }
            }, 720)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val size = min(w, h) * 0.72f
            val l = (w - size) / 2
            val t = (h - size) / 2
            val r = l + size
            val b = t + size
            canvas.drawRect(0f, 0f, w, t, shade)
            canvas.drawRect(0f, b, w, h, shade)
            canvas.drawRect(0f, t, l, b, shade)
            canvas.drawRect(r, t, w, b, shade)
            val c = size * 0.16f
            canvas.drawLine(l, t, l + c, t, corner); canvas.drawLine(l, t, l, t + c, corner)
            canvas.drawLine(r, t, r - c, t, corner); canvas.drawLine(r, t, r, t + c, corner)
            canvas.drawLine(l, b, l + c, b, corner); canvas.drawLine(l, b, l, b - c, corner)
            canvas.drawLine(r, b, r - c, b, corner); canvas.drawLine(r, b, r, b - c, corner)
            val y = t + 10 * density + (size - 20 * density) * progress
            canvas.drawLine(l + 14 * density, y, r - 14 * density, y, line)
        }
    }
}

/** EAN-13 codes starting with 0 are the same product as the 12-digit UPC-A; treat them as equal. */
fun normalizeCode(s: String): String {
    val t = s.trim()
    return if (t.length == 13 && t[0] == '0' && t.all { it.isDigit() }) t.substring(1) else t
}
