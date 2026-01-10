package com.ailife.rtosifycompanion

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast

class DialerActivity : Activity() {

    private lateinit var tvNumber: TextView
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
        setContentView(R.layout.activity_dialer)

        tvNumber = findViewById(R.id.tv_number)

        setupKeypad()

        findViewById<Button>(R.id.btn_delete).setOnClickListener {
            val current = tvNumber.text.toString()
            if (current.isNotEmpty()) {
                tvNumber.text = current.substring(0, current.length - 1)
            }
        }

        findViewById<Button>(R.id.btn_call).setOnClickListener {
            val number = tvNumber.text.toString()
            if (number.isNotEmpty()) {
                makeCall(number)
            } else {
                Toast.makeText(this, getString(R.string.dialer_empty_number), Toast.LENGTH_SHORT).show()
            }
        }

        // Bind to BluetoothService
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupKeypad() {
        val gridLayout = findViewById<GridLayout>(R.id.gridLayoutDialer)
        for (i in 0 until gridLayout.childCount) {
            val view = gridLayout.getChildAt(i)
            if (view is Button) {
                val text = view.text.toString()
                if (text.length == 1 && text[0].isDigit()) {
                    view.setOnClickListener {
                        tvNumber.append(text)
                    }
                }
            }
        }
    }

    private fun makeCall(number: String) {
        if (isBound && bluetoothService != null) {
            if (bluetoothService?.isConnected == true) {
                bluetoothService?.sendMakeCall(number)
                Toast.makeText(this, getString(R.string.dialer_calling, number), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
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
