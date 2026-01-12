package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class NetworkSettingsActivity : AppCompatActivity() {

    private lateinit var btnPairForInternet: Button
    private lateinit var checkBoxBtFallback: CheckBox
    private lateinit var checkBoxMainActivity: CheckBox
    private lateinit var checkBoxAlways: CheckBox
    private lateinit var tvCurrentWifi: TextView
    private lateinit var tvPhoneIp: TextView
    private lateinit var tvWatchWifi: TextView
    private lateinit var tvWatchIp: TextView
    private lateinit var devicePrefManager: DevicePrefManager
    
    private var bluetoothService: BluetoothService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BluetoothService.LocalBinder
            bluetoothService = binder?.getService()
            bluetoothService?.callback = serviceCallback
            serviceBound = true
            runOnUiThread { updateWifiStatus() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService?.callback = null
            bluetoothService = null
            serviceBound = false
        }
    }

    private val serviceCallback = object : BluetoothService.ServiceCallback {
        override fun onStatusChanged(status: String) {}
        override fun onDeviceConnected(deviceName: String) {}
        override fun onDeviceDisconnected() {}
        override fun onError(message: String) {}
        override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
        override fun onAppListReceived(appsJson: String) {}
        override fun onUploadProgress(progress: Int) {}
        override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
        override fun onFileListReceived(path: String, filesJson: String) {}
        override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean,
            ipAddress: String?
        ) {
            runOnUiThread {
                tvWatchWifi.text = if (wifiEnabled) wifiSsid else "Disabled"
                tvWatchIp.text = ipAddress ?: "N/A"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_settings)

        devicePrefManager = DevicePrefManager(this)
        val prefs = devicePrefManager.getGlobalPrefs()

        // Bind to BluetoothService
        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Initialize views
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        
        btnPairForInternet = findViewById(R.id.btnPairForInternet)
        checkBoxBtFallback = findViewById(R.id.checkBoxBtFallback)
        checkBoxMainActivity = findViewById(R.id.checkBoxMainActivity)
        checkBoxAlways = findViewById(R.id.checkBoxAlways)
        tvCurrentWifi = findViewById(R.id.tvCurrentWifi)
        tvPhoneIp = findViewById(R.id.tvPhoneIp)
        tvWatchWifi = findViewById(R.id.tvWatchWifi)
        tvWatchIp = findViewById(R.id.tvWatchIp)

        // Pairing button handler
        btnPairForInternet.setOnClickListener {
            // Launch the pairing activity
            val intent = Intent(this, WiFiPairingActivity::class.java)
            startActivity(intent)
        }

        // Load current setting from device prefs
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()
        val currentRule = devicePrefs.getInt("wifi_activation_rule", BluetoothService.WIFI_RULE_BT_FALLBACK)
        
        checkBoxAlways.isChecked = (currentRule and BluetoothService.WIFI_RULE_ALWAYS) != 0
        checkBoxBtFallback.isChecked = (currentRule and BluetoothService.WIFI_RULE_BT_FALLBACK) != 0
        checkBoxMainActivity.isChecked = (currentRule and BluetoothService.WIFI_RULE_MAINACTIVITY) != 0

        updateCheckboxEnableState()

        // Checkbox logic: make them mutually exclusive
        checkBoxAlways.setOnClickListener {
            if (checkBoxAlways.isChecked) {
                checkBoxBtFallback.isChecked = false
                checkBoxMainActivity.isChecked = false
            }
            updateCheckboxEnableState()
            saveActivationRule()
        }

        checkBoxBtFallback.setOnClickListener {
            saveActivationRule()
        }

        checkBoxMainActivity.setOnClickListener {
            saveActivationRule()
        }

        // Display current WiFi status
        updateWifiStatus()
        updatePairingButton()
    }
    
    private fun updatePairingButton() {
        // Check if WiFi is already paired by checking for encryption key
        val mac = bluetoothService?.getConnectedDeviceMac()
        val isPaired = mac != null && bluetoothService?.isPairedWithCurrentDevice() == true
        
        if (isPaired) {
            btnPairForInternet.text = getString(R.string.wifi_already_paired)
        } else {
            btnPairForInternet.text = getString(R.string.network_pair_for_internet)
        }
    }

    private fun updateCheckboxEnableState() {
        val alwaysChecked = checkBoxAlways.isChecked
        checkBoxBtFallback.isEnabled = !alwaysChecked
        checkBoxMainActivity.isEnabled = !alwaysChecked
    }

    private fun saveActivationRule() {
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()
        var newRule = 0
        if (checkBoxAlways.isChecked) {
            newRule = BluetoothService.WIFI_RULE_ALWAYS
        } else {
            if (checkBoxBtFallback.isChecked) newRule = newRule or BluetoothService.WIFI_RULE_BT_FALLBACK
            if (checkBoxMainActivity.isChecked) newRule = newRule or BluetoothService.WIFI_RULE_MAINACTIVITY
        }
        
        devicePrefs.edit().putInt("wifi_activation_rule", newRule).apply()
        
        // Sync WiFi rule to companion 
        bluetoothService?.syncWifiRuleToCompanion(newRule)
        
        // Notify TransportManager immediately
        bluetoothService?.notifyWifiRuleChanged()
        
        val ruleNames = mutableListOf<String>()
        if ((newRule and BluetoothService.WIFI_RULE_ALWAYS) != 0) {
            ruleNames.add(getString(R.string.network_rule_always))
        } else {
            if ((newRule and BluetoothService.WIFI_RULE_BT_FALLBACK) != 0) ruleNames.add(getString(R.string.network_rule_bt_fallback))
            if ((newRule and BluetoothService.WIFI_RULE_MAINACTIVITY) != 0) ruleNames.add(getString(R.string.network_rule_mainactivity))
        }
        
        val ruleDisplayName = if (ruleNames.isEmpty()) "Unknown" else ruleNames.joinToString(" + ")
        Toast.makeText(this, "WiFi rule set: $ruleDisplayName", Toast.LENGTH_SHORT).show()
    }

    private fun showEncryptionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.network_encryption_info_title)
            .setMessage(R.string.network_encryption_info_message)
            .setPositiveButton(R.string.network_encryption_finish) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun updateWifiStatus() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wm.isWifiEnabled) {
            val info = wm.connectionInfo
            if (info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED) {
                val ssid = info.ssid.replace("\"", "")
                tvCurrentWifi.text = if (ssid == "<unknown ssid>") "Connected" else ssid
                
                // Update Phone IP
                val ip = info.ipAddress
                tvPhoneIp.text = if (ip == 0) "N/A" else String.format(
                    "%d.%d.%d.%d",
                    (ip and 0xff),
                    (ip shr 8 and 0xff),
                    (ip shr 16 and 0xff),
                    (ip shr 24 and 0xff)
                )
            } else {
                tvCurrentWifi.text = getString(R.string.network_not_on_wifi)
                tvPhoneIp.text = "N/A"
            }
        } else {
            tvCurrentWifi.text = "WiFi Disabled"
            tvPhoneIp.text = "N/A"
        }
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
        updatePairingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
