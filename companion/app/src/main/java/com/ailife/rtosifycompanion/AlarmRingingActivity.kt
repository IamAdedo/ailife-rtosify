package com.ailife.rtosifycompanion

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AlarmRingingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
        const val EXTRA_ALARM_HOUR = "alarm_hour"
        const val EXTRA_ALARM_MINUTE = "alarm_minute"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Show over lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_alarm_ringing)

        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: ""
        val label = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: ""
        val hour = intent.getIntExtra(EXTRA_ALARM_HOUR, 0)
        val minute = intent.getIntExtra(EXTRA_ALARM_MINUTE, 0)

        val tvTime = findViewById<TextView>(R.id.tvAlarmTime)
        val tvLabel = findViewById<TextView>(R.id.tvAlarmLabel)
        val btnDismiss = findViewById<Button>(R.id.btnDismiss)
        val btnSnooze = findViewById<Button>(R.id.btnSnooze)

        tvTime.text = String.format("%02d:%02d", hour, minute)
        
        if (label.isNotEmpty()) {
            tvLabel.text = label
            tvLabel.visibility = TextView.VISIBLE
        }

        btnDismiss.setOnClickListener {
            AlarmReceiver.stopAlarmSound()
            AlarmReceiver.dismissAlarm(this, alarmId)
            finish()
        }

        btnSnooze.setOnClickListener {
            AlarmReceiver.stopAlarmSound()
            AlarmReceiver.snoozeAlarm(this, alarmId, label)
            finish()
        }
    }

    override fun onBackPressed() {
        // Prevent back button from dismissing alarm
    }
}
