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
import android.widget.EditText
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosify.communication.TransportManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NetworkSettingsActivity : AppCompatActivity() {

    private lateinit var btnPairForInternet: Button
    private lateinit var checkBoxBtFallback: CheckBox
    private lateinit var checkBoxMainActivity: CheckBox
    private lateinit var checkBoxAlways: CheckBox
    private lateinit var tvCurrentWifi: TextView
    private lateinit var tvPhoneIp: TextView
    private lateinit var tvWatchWifi: TextView
    private lateinit var tvWatchIp: TextView
    private lateinit var checkBoxFixedIp: CheckBox
    private lateinit var layoutFixedIp: View
    private lateinit var etFixedIp: EditText
    
    // Internet Settings Views
    private lateinit var checkBoxInternetBtFallback: CheckBox
    private lateinit var checkBoxInternetMainActivity: CheckBox
    private lateinit var checkBoxInternetAlways: CheckBox
    private lateinit var etSignalingUrl: EditText
    
    private lateinit var devicePrefManager: DevicePrefManager
    
    private var bluetoothService: BluetoothService? = null
    private var serviceBound = false
    private var watchStatusPollingJob: Job? = null

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
        checkBoxFixedIp = findViewById(R.id.checkBoxFixedIp)
        layoutFixedIp = findViewById(R.id.layoutFixedIp)
        etFixedIp = findViewById(R.id.etFixedIp)
        
        // Internet Views
        checkBoxInternetBtFallback = findViewById(R.id.checkBoxInternetBtFallback)
        checkBoxInternetMainActivity = findViewById(R.id.checkBoxInternetMainActivity)
        checkBoxInternetAlways = findViewById(R.id.checkBoxInternetAlways)
        etSignalingUrl = findViewById(R.id.etSignalingUrl)

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
        
        // Load Internet Settings
        val internetRule = devicePrefs.getInt("internet_activation_rule", 0)
        checkBoxInternetAlways.isChecked = (internetRule and TransportManager.INTERNET_RULE_ALWAYS) != 0
        checkBoxInternetBtFallback.isChecked = (internetRule and TransportManager.INTERNET_RULE_BT_FALLBACK) != 0
        checkBoxInternetMainActivity.isChecked = (internetRule and TransportManager.INTERNET_RULE_MAINACTIVITY) != 0
        
        etSignalingUrl.setText(devicePrefs.getString("internet_signaling_url", "ws://192.168.1.10:8080"))

        // Load Fixed IP settings
        val fixedIpEnabled = devicePrefs.getBoolean("wifi_fixed_ip_enabled", false)
        val fixedIpAddress = devicePrefs.getString("wifi_fixed_ip_address", "")
        
        checkBoxFixedIp.isChecked = fixedIpEnabled
        etFixedIp.setText(fixedIpAddress)
        layoutFixedIp.visibility = if (fixedIpEnabled) View.VISIBLE else View.GONE

        updateCheckboxEnableState()
        updateInternetCheckboxEnableState()

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
        
        // Internet Checkbox Logic
        checkBoxInternetAlways.setOnClickListener {
            if (checkBoxInternetAlways.isChecked) {
                checkBoxInternetBtFallback.isChecked = false
                checkBoxInternetMainActivity.isChecked = false
            }
            updateInternetCheckboxEnableState()
            saveInternetActivationRule()
        }

        checkBoxInternetBtFallback.setOnClickListener {
            saveInternetActivationRule()
        }

        checkBoxInternetMainActivity.setOnClickListener {
            saveInternetActivationRule()
        }
        
        etSignalingUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                devicePrefs.edit().putString("internet_signaling_url", s.toString().trim()).apply()
                // Notify TransportManager if needed, or it will pick it up on next connection attempt
            }
        })

        checkBoxFixedIp.setOnCheckedChangeListener { _, isChecked ->
            layoutFixedIp.visibility = if (isChecked) View.VISIBLE else View.GONE
            devicePrefs.edit().putBoolean("wifi_fixed_ip_enabled", isChecked).apply()
            // Notify TransportManager
            bluetoothService?.notifyWifiRuleChanged()
        }

        etFixedIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                devicePrefs.edit().putString("wifi_fixed_ip_address", s.toString().trim()).apply()
                // Notify TransportManager (might need debouncing for efficiency, but it's okay for now)
                bluetoothService?.notifyWifiRuleChanged()
            }
        })

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

    private fun updateInternetCheckboxEnableState() {
        val alwaysChecked = checkBoxInternetAlways.isChecked
        checkBoxInternetBtFallback.isEnabled = !alwaysChecked
        checkBoxInternetMainActivity.isEnabled = !alwaysChecked
    }

    private fun saveInternetActivationRule() {
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()
        var newRule = 0
        if (checkBoxInternetAlways.isChecked) {
            newRule = TransportManager.INTERNET_RULE_ALWAYS
        } else {
            if (checkBoxInternetBtFallback.isChecked) newRule = newRule or TransportManager.INTERNET_RULE_BT_FALLBACK
            if (checkBoxInternetMainActivity.isChecked) newRule = newRule or TransportManager.INTERNET_RULE_MAINACTIVITY
        }
        
        devicePrefs.edit().putInt("internet_activation_rule", newRule).apply()
        
        // Notify TransportManager and Companion immediately
        bluetoothService?.notifyInternetSettingsChanged()
        bluetoothService?.notifyWifiRuleChanged() // Refresh transport evaluation
        
        Toast.makeText(this, "Internet rule updated", Toast.LENGTH_SHORT).show()
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
        startWatchStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        stopWatchStatusPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchStatusPolling()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    private fun startWatchStatusPolling() {
        stopWatchStatusPolling()
        watchStatusPollingJob = MainScope().launch {
            while (true) {
                if (bluetoothService?.isConnected == true) {
                    bluetoothService?.requestWatchStatus()
                }
                delay(2000) // Poll every 2 seconds
            }
        }
    }
    
    private fun stopWatchStatusPolling() {
        watchStatusPollingJob?.cancel()
        watchStatusPollingJob = null
    }
}
