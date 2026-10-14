package com.relentless.alarm

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Camera barcode scanner used to register an item and to find it again in the morning.
 * CameraX + Google ML Kit (bundled model, works offline): reads every common 1D/2D format at any angle,
 * with continuous autofocus, tap-to-focus and a flashlight.
 */
class ItemScanner(ctx: Context) : FrameLayout(ctx), LifecycleOwner {
    companion object {
        /** Shared so a released scanner never leaves CameraX posting to a dead executor. */
        private val analysisExecutor = Executors.newSingleThreadExecutor()
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    private val reader = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )
    private val preview = PreviewView(ctx).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val finder = Viewfinder(ctx)
    private val torchButton: TextView
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var binding = false
    private var released = false
    private var torchOn = false
    private val lastSeen = HashMap<String, Long>()

    /** Called (on the main thread) with each code the camera reads; the same code at most every 1.5 s. */
    var onScan: ((String) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    init {
        registry.currentState = Lifecycle.State.CREATED
        background = ctx.rounded(Color.BLACK, 20)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        addView(preview, LayoutParams(MATCH, MATCH))
        addView(finder, LayoutParams(MATCH, MATCH))

        torchButton = ctx.label("🔦", 20f, center = true).apply {
            background = ctx.rounded(0x99000000.toInt(), 24)
            setOnClickListener { setTorch(!torchOn) }
            contentDescription = "Flashlight"
        }
        addView(torchButton, LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(0, ctx.dp(12), ctx.dp(12), 0)
        })

        // Tap to focus on a spot (helps with small barcodes up close).
        @SuppressLint("ClickableViewAccessibility")
        preview.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val cam = camera
                if (cam != null) {
                    val point = preview.meteringPointFactory.createPoint(ev.x, ev.y)
                    try {
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    } catch (_: Exception) {
                    }
                }
            }
            true
        }
    }

    fun resume() {
        if (released) return
        registry.currentState = Lifecycle.State.RESUMED
        if (camera == null && !binding) bind()
    }

    fun pause() {
        if (released) return
        if (torchOn) setTorch(false)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Frees the camera for good. Call when the screen goes away. */
    fun release() {
        if (released) return
        released = true
        analysis?.clearAnalyzer()
        registry.currentState = Lifecycle.State.DESTROYED
        try {
            reader.close()
        } catch (_: Exception) {
        }
    }

    private fun bind() {
        binding = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            binding = false
            if (released) return@addListener
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { analyze(it) }
                this.analysis = analysis
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) {
                Log.e("ItemScanner", "camera bind failed", e)
                onError?.invoke(e)
            }
        }, mainExecutor)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || released) {
            proxy.close()
            return
        }
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val task = try {
            reader.process(img)
        } catch (e: Exception) {
            proxy.close()
            return
        }
        task
            .addOnSuccessListener(mainExecutor) { codes ->
                val now = System.currentTimeMillis()
                for (c in codes) {
                    val v = c.rawValue ?: continue
                    if (v.isEmpty()) continue
                    if (now - (lastSeen[v] ?: 0L) < 1500) continue
                    lastSeen[v] = now
                    onScan?.invoke(v)
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        try {
            camera?.cameraControl?.enableTorch(on)
        } catch (_: Exception) {
        }
        torchButton.background = context.rounded(if (on) Colors.WARN else 0x99000000.toInt(), 24)
    }

    /** Green (found) or red (wrong item) flash of the viewfinder, with a haptic tap. */
    fun feedback(ok: Boolean) {
        finder.flash(if (ok) Colors.OK else Colors.ACCENT)
        val h = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.LONG_PRESS
        }
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
