package com.ailife.rtosify

import android.content.Intent
import android.os.Bundle
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity

class FindPhoneActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure it shows over lockscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_find_phone)
        val rootLayout = findViewById<View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)

        findViewById<MaterialButton>(R.id.btnStopAlarm).setOnClickListener {
            // Stop alarm via service
            val intent = Intent(this, BluetoothService::class.java).apply {
                action = "STOP_ALARM"
            }
            startService(intent)
            finish()
        }
    }
}
