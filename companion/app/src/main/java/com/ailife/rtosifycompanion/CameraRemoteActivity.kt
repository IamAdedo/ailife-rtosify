package com.ailife.rtosifycompanion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

class CameraRemoteActivity : AppCompatActivity() {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
            
            // Send start command when connected
            bluetoothService?.sendMessage(ProtocolHelper.createCameraStart())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    private val frameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FRAME_RECEIVED) {
                val base64 = intent.getStringExtra(EXTRA_FRAME_DATA)
                if (base64 != null) {
                    try {
                        val decodedString = Base64.decode(base64, Base64.DEFAULT)
                        val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        imageView.setImageBitmap(decodedByte)
                        statusText.visibility = View.GONE
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_remote)
        val rootLayout = findViewById<View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)

        imageView = findViewById(R.id.img_viewfinder)
        statusText = findViewById(R.id.tv_status)
        val btnShutter = findViewById<Button>(R.id.btn_shutter)

        btnShutter.setOnClickListener {
            if (isBound && bluetoothService != null) {
                bluetoothService?.sendMessage(ProtocolHelper.createCameraShutter())
            }
        }

        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            frameReceiver,
            IntentFilter(ACTION_FRAME_RECEIVED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(frameReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.sendMessage(ProtocolHelper.createCameraStop())
            unbindService(serviceConnection)
            isBound = false
        }
    }

    companion object {
        const val ACTION_FRAME_RECEIVED = "com.ailife.rtosifycompanion.ACTION_FRAME_RECEIVED"
        const val EXTRA_FRAME_DATA = "frame_data"
    }
}
