package com.ailife.rtosify

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosify.ShareData

class ShareToWatchActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var tvShareStatus: TextView
    private lateinit var tvSharePercentage: TextView
    private lateinit var progressBarShare: ProgressBar
    
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@ShareToWatchActivity
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
                    Toast.makeText(this, "Text shared to watch", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                // Handle file sharing
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    tvShareStatus.text = "Sending file to watch..."
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
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {}
    override fun onScanResult(devices: List<BluetoothDevice>) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
    override fun onWatchStatusUpdated(batteryLevel: Int, isCharging: Boolean, wifiSsid: String, wifiEnabled: Boolean, dndEnabled: Boolean, ipAddress: String?) {}
    override fun onHealthDataUpdated(healthData: HealthDataUpdate) {}
    override fun onHealthHistoryReceived(historyData: HealthHistoryResponse) {}
    override fun onHealthSettingsReceived(settings: HealthSettingsUpdate) {}
    override fun onPreviewReceived(path: String, imageBase64: String?) {}
    override fun onWifiScanResultsReceived(results: List<WifiScanResultData>) {}
    override fun onBatteryDetailReceived(data: BatteryDetailData) {}
    override fun onDeviceInfoReceived(info: DeviceInfoData) {}
    override fun onShellCommandResponse(response: ShellCommandResponse) {}
    override fun onPermissionInfoReceived(info: PermissionInfoData) {}
    override fun onWifiKeyAck(success: Boolean) {}
    override fun onWifiTestAck(success: Boolean) {}
}
