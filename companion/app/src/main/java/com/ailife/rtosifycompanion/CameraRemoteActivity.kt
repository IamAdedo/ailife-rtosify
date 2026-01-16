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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

class CameraRemoteActivity : AppCompatActivity() {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var isRecording = false
    private var recordStartTime = 0L

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var tvTimer: TextView

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordStartTime
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / (1000 * 60)) % 60
            tvTimer.text = String.format("%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_FRAME_RECEIVED -> {
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
                ACTION_RECORD_STATUS_CHANGED -> {
                    val recording = intent.getBooleanExtra(EXTRA_RECORD_STATUS, false)
                    if (recording) {
                        if (!isRecording) startRecording()
                    } else {
                        if (isRecording) stopRecording()
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
        tvTimer = findViewById(R.id.tv_timer)
        val btnShutter = findViewById<Button>(R.id.btn_shutter)
        btnRecord = findViewById(R.id.btn_record)

        btnShutter.setOnClickListener {
            if (isBound && bluetoothService != null) {
                bluetoothService?.sendMessage(ProtocolHelper.createCameraShutter())
            }
        }

        btnRecord.setOnClickListener {
            if (isBound && bluetoothService != null) {
                if (!isRecording) {
                    bluetoothService?.sendMessage(ProtocolHelper.createCameraRecordStart())
                    startRecording()
                } else {
                    bluetoothService?.sendMessage(ProtocolHelper.createCameraRecordStop())
                    stopRecording()
                }
            }
        }

        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_FRAME_RECEIVED)
            addAction(ACTION_RECORD_STATUS_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun startRecording() {
        isRecording = true
        btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
        tvTimer.visibility = View.VISIBLE
        recordStartTime = System.currentTimeMillis()
        timerHandler.post(timerRunnable)
    }

    private fun stopRecording() {
        isRecording = false
        btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF0000.toInt())
        tvTimer.visibility = View.GONE
        timerHandler.removeCallbacks(timerRunnable)
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
        const val ACTION_RECORD_STATUS_CHANGED = "com.ailife.rtosifycompanion.ACTION_RECORD_STATUS_CHANGED"
        const val EXTRA_FRAME_DATA = "frame_data"
        const val EXTRA_RECORD_STATUS = "record_status"
    }
}
