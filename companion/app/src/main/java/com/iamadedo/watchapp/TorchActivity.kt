package com.iamadedo.watchapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Torch / flashlight mode — fills the entire screen with maximum-brightness white.
 * Triggered by TORCH_CONTROL message or quick-settings shortcut.
 * Tap anywhere to exit.
 */
class TorchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Maximum brightness
        val lp = window.attributes
        lp.screenBrightness = 1.0f
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = View(this).apply {
            setBackgroundColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        finish() // Exit when screen leaves foreground
    }
}
