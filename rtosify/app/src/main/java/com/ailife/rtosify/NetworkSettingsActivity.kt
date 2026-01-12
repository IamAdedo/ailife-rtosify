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
    private lateinit var devicePrefManager: DevicePrefManager
    
    private var bluetoothService: BluetoothService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BluetoothService.LocalBinder
            bluetoothService = binder?.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            serviceBound = false
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

        // Pairing button handler
        btnPairForInternet.setOnClickListener {
            // Launch the pairing activity
            val intent = Intent(this, WiFiPairingActivity::class.java)
            startActivity(intent)
        }

        // Load current setting from device prefs
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()
        val currentRule = devicePrefs.getInt("wifi_activation_rule", BluetoothService.WIFI_RULE_BT_FALLBACK)
        when (currentRule) {
            BluetoothService.WIFI_RULE_BT_FALLBACK -> checkBoxBtFallback.isChecked = true
            BluetoothService.WIFI_RULE_MAINACTIVITY -> checkBoxMainActivity.isChecked = true
            BluetoothService.WIFI_RULE_ALWAYS -> checkBoxAlways.isChecked = true
        }

        // Checkbox logic: "Always" disables others
        checkBoxAlways.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkBoxBtFallback.isEnabled = false
                checkBoxMainActivity.isEnabled = false
                checkBoxBtFallback.isChecked = false
                checkBoxMainActivity.isChecked = false
                saveActivationRule()
            } else {
                checkBoxBtFallback.isEnabled = true
                checkBoxMainActivity.isEnabled = true
            }
        }

        checkBoxBtFallback.setOnCheckedChangeListener { _, _ -> saveActivationRule() }
        checkBoxMainActivity.setOnCheckedChangeListener { _, _ -> saveActivationRule() }

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

    private fun saveActivationRule() {
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()
        val newRule = when {
            checkBoxAlways.isChecked -> BluetoothService.WIFI_RULE_ALWAYS
            checkBoxMainActivity.isChecked -> BluetoothService.WIFI_RULE_MAINACTIVITY
            checkBoxBtFallback.isChecked -> BluetoothService.WIFI_RULE_BT_FALLBACK
            else -> BluetoothService.WIFI_RULE_BT_FALLBACK
        }
        
        devicePrefs.edit().putInt("wifi_activation_rule", newRule).apply()
        
        // Sync WiFi rule to companion (for "Always" and "BT Fallback" rules)
        // These need to be synced because phone can't reach companion via Bluetooth when disconnected
        if (newRule == BluetoothService.WIFI_RULE_ALWAYS || newRule == BluetoothService.WIFI_RULE_BT_FALLBACK) {
            bluetoothService?.syncWifiRuleToCompanion(newRule)
        }
        
        val ruleName = when (newRule) {
            BluetoothService.WIFI_RULE_BT_FALLBACK -> getString(R.string.network_rule_bt_fallback)
            BluetoothService.WIFI_RULE_MAINACTIVITY -> getString(R.string.network_rule_mainactivity)
            BluetoothService.WIFI_RULE_ALWAYS -> getString(R.string.network_rule_always)
            else -> "Unknown"
        }
        Toast.makeText(this, "WiFi rule set: $ruleName", Toast.LENGTH_SHORT).show()
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
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network: Network? = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid.replace("\"", "")
                tvCurrentWifi.text = if (ssid != "<unknown ssid>") ssid else getString(R.string.network_not_on_wifi)
            } else {
                tvCurrentWifi.text = getString(R.string.network_not_on_wifi)
            }
        } else {
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null && wifiManager.isWifiEnabled) {
                val ssid = wifiInfo.ssid.replace("\"", "")
                tvCurrentWifi.text = if (ssid.isNotEmpty()) ssid else getString(R.string.network_not_on_wifi)
            } else {
                tvCurrentWifi.text = getString(R.string.network_not_on_wifi)
            }
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
