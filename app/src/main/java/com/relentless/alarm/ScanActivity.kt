package com.relentless.alarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Register the item for a "Find the item" mission, or test-scan an item that's already registered.
 * Register mode returns the extras "code" and "note".
 */
class ScanActivity : Activity() {
    companion object {
        /** Test mode: the code we expect to see. */
        const val EXTRA_EXPECT = "expect"
        const val EXTRA_NOTE = "note"
        private val SUGGESTIONS = listOf(
            "Toothpaste", "Shampoo", "Coffee", "Cereal box", "Hand soap", "Deodorant", "Vitamins", "Kettle",
        )
    }

    private lateinit var scanner: ItemScanner
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var expect: String? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expect = intent.getStringExtra(EXTRA_EXPECT)
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        val testing = expect != null

        val root = vertical(20)
        root.addView(titleBar(if (testing) "Test scan" else "Register an item"), llp())
        root.addView(
            label(
                if (testing) "Scan your ${note.ifBlank { "item" }} to check it reads quickly, even in dim light."
                else "Choose something that stays far from your bed, like toothpaste in the bathroom or coffee in the kitchen. " +
                    "Each morning you'll have to walk there and scan it.",
                15f, Colors.SUB
            ),
            llp(top = 8)
        )

        scanner = ItemScanner(this)
        scanner.onScan = { onCode(it) }
        scanner.onError = {
            status.text = "Camera unavailable. Close other camera apps and try again."
            status.setTextColor(Colors.WARN)
        }
        root.addView(scanner, llp(MATCH, 0, 1f, top = 16))

        status = label("Point the camera at a barcode or QR code", 15f, Colors.SUB, center = true)
        root.addView(status, llp(top = 14))
        root.addView(
            label("Tip: tap 🔦 for the flashlight. Hold the phone 10–20 cm from the code.", 13f, Colors.DIM, center = true),
            llp(top = 6)
        )
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun onCode(code: String) {
        if (done) return
        val exp = expect
        if (exp != null) {
            if (normalizeCode(code) == normalizeCode(exp)) {
                done = true
                scanner.feedback(true)
                status.text = "✓ Works! This is the one you'll scan in the morning."
                status.setTextColor(Colors.OK)
                handler.postDelayed({ finish() }, 1600)
            } else {
                scanner.feedback(false)
                status.text = "That's a different barcode from the one you registered."
                status.setTextColor(Colors.ACCENT)
            }
            return
        }
        done = true
        scanner.feedback(true)
        scanner.pause()
        status.text = "✓ Barcode captured"
        status.setTextColor(Colors.OK)
        askName(code)
    }

    private fun askName(code: String) {
        val box = vertical(20)
        box.addView(label("What is it, and where does it live?", 15f, Colors.SUB), llp())
        val edit = EditText(this).apply {
            hint = "e.g. Toothpaste in the bathroom"
            setTextColor(Colors.TEXT)
            setHintTextColor(Colors.SUB)
            background = rounded(Colors.CARD_HI, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine()
        }
        val chips = horizontal()
        for (s in SUGGESTIONS) {
            chips.addView(pill(s) {
                edit.setText(s)
                edit.setSelection(s.length)
            }, llp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, llp(top = 12))
        box.addView(edit, llp(top = 12))

        AlertDialog.Builder(this)
            .setTitle("Name this item")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save item") { _, _ ->
                val name = edit.text.toString().trim().ifBlank { "My item" }
                setResult(RESULT_OK, Intent().putExtra("code", code).putExtra("note", name))
                Toast.makeText(this, "Saved: $name", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Scan again") { _, _ ->
                done = false
                status.text = "Point the camera at a barcode or QR code"
                status.setTextColor(Colors.SUB)
                scanner.resume()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanner.resume()
        } else {
            Toast.makeText(this, "The camera is needed to scan your item", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!done && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner.resume()
    }

    override fun onPause() {
        scanner.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
