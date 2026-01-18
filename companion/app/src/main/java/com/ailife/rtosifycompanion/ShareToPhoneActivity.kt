package com.ailife.rtosifycompanion

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosifycompanion.ShareData

class ShareToPhoneActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var tvShareStatus: TextView
    private lateinit var tvSharePercentage: TextView
    private lateinit var progressBarShare: ProgressBar
    
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@ShareToPhoneActivity
            isBound = true
            handleIntent(intent)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_sync)
        val rootLayout = findViewById<android.view.View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)

        tvShareStatus = findViewById(R.id.tvShareStatus)
        tvSharePercentage = findViewById(R.id.tvSharePercentage)
        progressBarShare = findViewById(R.id.progressBarShare)

        val serviceIntent = Intent(this, BluetoothService::class.java)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            if (bluetoothService?.callback == this) {
                bluetoothService?.callback = null
            }
            unbindService(connection)
            isBound = false
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type ?: "text/plain"
            
            if (type.startsWith("text/")) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                
                if (text != null) {
                    val shareData = ShareData(title, text, null, type)
                    bluetoothService?.sendShareSync(shareData)
                    Toast.makeText(this, "Text shared to phone", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                // Handle file sharing
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    tvShareStatus.text = "Sending file to phone..."
                    bluetoothService?.sendUriFile(uri)
                } else {
                    Toast.makeText(this, "No file to share", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    override fun onUploadProgress(progress: Int) {
        runOnUiThread {
            progressBarShare.progress = progress
            tvSharePercentage.text = "$progress%"
            if (progress >= 100) {
                Toast.makeText(this, "Transfer complete", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, "Error: $message", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Unused callbacks
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String, transportType: String) {}
    override fun onDeviceDisconnected() {}

    override fun onScanResult(devices: List<BluetoothDevice>) {}
    override fun onDownloadProgress(progress: Int) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
    override fun onWatchStatusUpdated(batteryLevel: Int, isCharging: Boolean, wifiSsid: String, wifiEnabled: Boolean, dndEnabled: Boolean, ipAddress: String?, wifiState: String?) {}
    override fun onPhoneBatteryUpdated(battery: Int, isCharging: Boolean) {}
}
