package com.relentless.alarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Registers the barcode used by a Barcode mission. Returns it as the "code" extra. */
class ScanActivity : Activity() {
    private var scanner: DecoratedBarcodeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical(20)
        root.addView(label("Register a barcode", 24f, bold = true), llp())
        root.addView(
            label(
                "Pick something far from your bed — toothpaste, shampoo, a coffee bag in the kitchen. " +
                    "Every morning you'll have to walk there and scan it.",
                15f, Colors.SUB
            ),
            llp(top = 6)
        )
        val view = DecoratedBarcodeView(this)
        view.setStatusText("Point the camera at a barcode or QR code")
        view.decodeContinuous { result ->
            val text = result.text
            if (!text.isNullOrEmpty()) {
                view.pause()
                setResult(RESULT_OK, Intent().putExtra("code", text))
                finish()
            }
        }
        root.addView(view, llp(MATCH, 0, 1f, top = 16))
        root.addView(button("Cancel", Colors.CARD_HI) { finish() }, llp(top = 16))
        setContentView(root)
        scanner = view

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanner?.resume()
        } else {
            Toast.makeText(this, "Camera permission is needed for the barcode mission", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner?.resume()
    }

    override fun onPause() {
        scanner?.pause()
        super.onPause()
    }
}
