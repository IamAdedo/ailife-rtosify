package com.ailife.rtosifycompanion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class FindDeviceAlarmActivity : AppCompatActivity() {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var findDeviceRingtone: Ringtone? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BluetoothService.LocalBinder
            bluetoothService = binder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    private val stopAlarmReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.ailife.rtosify.STOP_ALARM") {
                Log.i("FindDeviceAlarm", "Stop alarm broadcast received")
                findDeviceRingtone?.stop()
                findDeviceRingtone = null
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the screen turns on and shows over lockscreen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_find_device_alarm)

        val btnStopAlarm = findViewById<Button>(R.id.btnStopAlarm)
        btnStopAlarm.setOnClickListener {
            stopAlarmAndNotify()
        }

        registerReceiver(stopAlarmReceiver, android.content.IntentFilter("com.ailife.rtosify.STOP_ALARM"), RECEIVER_EXPORTED)
        startAlarm()
        bindToBluetoothService()
    }

    private fun startAlarm() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            findDeviceRingtone = RingtoneManager.getRingtone(this, alarmUri).apply {
                @Suppress("DEPRECATION") streamType = AudioManager.STREAM_ALARM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
            Log.i("FindDeviceAlarm", "Alarm started at max volume")
        } catch (e: Exception) {
            Log.e("FindDeviceAlarm", "Error starting alarm: ${e.message}")
        }
    }

    private fun stopAlarmAndNotify() {
        findDeviceRingtone?.stop()
        findDeviceRingtone = null
        
        // Notify the phone that finding is finished/stopped
        bluetoothService?.sendFindDeviceCommand(false)
        finish()
    }

    private fun bindToBluetoothService() {
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        findDeviceRingtone?.stop()
        try {
            unregisterReceiver(stopAlarmReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onBackPressed() {
        // Prevent accidental exit, require clicking the stop button
        // Or we can just call stopAlarmAndNotify()
        stopAlarmAndNotify()
    }
}
