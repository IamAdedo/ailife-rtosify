package com.ailife.rtosifycompanion

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.Toast

class MediaControlActivity : Activity() {

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
        setContentView(R.layout.activity_media_control)

        // Bind to BluetoothService
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setupListeners()
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btn_play_pause).setOnClickListener {
            sendCommand(MediaControlData.CMD_PLAY_PAUSE)
        }

        findViewById<Button>(R.id.btn_next).setOnClickListener {
            sendCommand(MediaControlData.CMD_NEXT)
        }

        findViewById<Button>(R.id.btn_prev).setOnClickListener {
            sendCommand(MediaControlData.CMD_PREVIOUS)
        }

        findViewById<Button>(R.id.btn_vol_up).setOnClickListener {
            sendCommand(MediaControlData.CMD_VOL_UP)
        }

        findViewById<Button>(R.id.btn_vol_down).setOnClickListener {
            sendCommand(MediaControlData.CMD_VOL_DOWN)
        }
    }

    private fun sendCommand(command: String) {
        if (isBound && bluetoothService != null) {
            if (bluetoothService?.isConnected == true) {
                bluetoothService?.sendMediaCommand(command)
                // Toast removed as requested
            } else {
                Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
