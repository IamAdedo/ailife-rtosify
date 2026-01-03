package com.ailife.rtosifycompanion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class FindDeviceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure it shows over lockscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_find_device)

        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            // Stop alarm via service
            val intent = Intent(this, BluetoothService::class.java).apply {
                action = "STOP_ALARM"
            }
            startService(intent)
            finish()
        }
    }
}
