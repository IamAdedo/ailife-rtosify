package com.iamadedo.watchapp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen SOS countdown activity.
 *
 * Triggered by a long-press (5 s) on any configurable button.
 * Shows a 20-second countdown with a Cancel option.
 * On expiry it binds to BluetoothService and dispatches SOS_TRIGGERED to the phone.
 *
 * The phone then:
 *   1. Dials the local emergency number
 *   2. Sends SMS to emergency contacts with GPS location
 *   3. Starts a 30s location broadcast loop
 */
class SosActivity : Activity() {

    companion object {
        const val SOS_COUNTDOWN_MS = 20_000L
        const val SOS_TICK_MS = 100L
    }

    private lateinit var tvCountdown: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: Button
    private lateinit var vibrator: Vibrator

    private var countdown: CountDownTimer? = null
    private var bluetoothService: BluetoothService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? BluetoothService.LocalBinder ?: return
            bluetoothService = localBinder.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and show over lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_sos)

        tvCountdown = findViewById(R.id.tv_sos_countdown)
        tvStatus    = findViewById(R.id.tv_sos_status)
        progressBar = findViewById(R.id.progress_sos)
        btnCancel   = findViewById(R.id.btn_sos_cancel)
        vibrator    = getSystemService(VIBRATOR_SERVICE) as Vibrator

        progressBar.max = SOS_COUNTDOWN_MS.toInt()

        btnCancel.setOnClickListener { cancelSos() }

        bindService(Intent(this, BluetoothService::class.java), serviceConnection, BIND_AUTO_CREATE)

        startCountdown()
    }

    private fun startCountdown() {
        // Urgent haptic on start
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1))

        countdown = object : CountDownTimer(SOS_COUNTDOWN_MS, SOS_TICK_MS) {
            override fun onTick(remaining: Long) {
                val seconds = (remaining / 1000) + 1
                tvCountdown.text = seconds.toString()
                progressBar.progress = remaining.toInt()
                if (remaining % 1000 < SOS_TICK_MS) {
                    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            override fun onFinish() {
                dispatchSos()
            }
        }.start()
    }

    private fun dispatchSos() {
        tvCountdown.text = "!"
        tvStatus.text = getString(R.string.sos_contacting_emergency)
        btnCancel.isEnabled = false
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 600, 200, 600), -1))

        // Ask BluetoothService to relay SOS to phone
        val sosIntent = Intent(this, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.SEND_SOS"
        }
        startService(sosIntent)

        // Close after a short delay so user can see "Contacting"
        handler.postDelayed({ finish() }, 3000)
    }

    private fun cancelSos() {
        countdown?.cancel()
        vibrator.cancel()
        finish()
    }

    override fun onDestroy() {
        countdown?.cancel()
        unbindService(serviceConnection)
        super.onDestroy()
    }
}
