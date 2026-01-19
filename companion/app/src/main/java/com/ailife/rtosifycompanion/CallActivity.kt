package com.ailife.rtosifycompanion

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.util.Log

class CallActivity : AppCompatActivity() {

    private lateinit var tvName: TextView
    private lateinit var tvNumber: TextView
    private lateinit var tvStatus: TextView
    
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make sure it shows over lock screen and turns on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        
        setContentView(R.layout.activity_call)
        val rootLayout = findViewById<View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)

        tvName = findViewById(R.id.tv_caller_name)
        tvNumber = findViewById(R.id.tv_caller_number)
        tvStatus = findViewById(R.id.tv_call_status)

        val name = intent.getStringExtra("callerId") ?: getString(R.string.device_name_default)
        val number = intent.getStringExtra("number") ?: getString(R.string.call_unknown)

        tvName.text = name
        tvNumber.text = number

        findViewById<ImageButton>(R.id.btn_reject).setOnClickListener {
            rejectCall()
        }

        findViewById<ImageButton>(R.id.btn_answer).setOnClickListener {
            answerCall()
        }

        // Start vibration
        startVibration()

        // Bind to BluetoothService to send commands
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startVibration() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("vibrate_enabled", true)) return

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator?.hasVibrator() == true) {
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("CallActivity", "Error starting vibration: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("CallActivity", "Error stopping vibration: ${e.message}")
        }
    }

    private fun rejectCall() {
        if (isBound && bluetoothService?.isConnected == true) {
            bluetoothService?.sendRejectCall()
            finish()
        } else {
            Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun answerCall() {
        if (isBound && bluetoothService?.isConnected == true) {
            bluetoothService?.sendAnswerCall()
            tvStatus.text = getString(R.string.call_answered)
            // We might want to finish or stay to show call duration, 
            // but for now let's finish as the phone takes over.
            finish()
        } else {
            Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle state updates if already open
        val state = intent?.getStringExtra("state")
        if (state == "IDLE") {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVibration()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
