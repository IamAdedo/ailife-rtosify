package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
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

    // Status views
    private lateinit var tvActiveConnection: TextView
    private lateinit var indicatorBt: View
    private lateinit var indicatorLan: View
    private lateinit var indicatorInternet: View
    private lateinit var tvBtStatus: TextView
    private lateinit var tvLanStatus: TextView
    private lateinit var tvInternetStatus: TextView

    // LAN Rules
    private lateinit var radioGroupLan: RadioGroup
    private lateinit var radioLanDisabled: RadioButton
    private lateinit var radioLanBtFallback: RadioButton
    private lateinit var radioLanAppOpen: RadioButton
    private lateinit var radioLanBtOrApp: RadioButton
    private lateinit var radioLanAlways: RadioButton

    // Internet Rules
    private lateinit var radioGroupInternet: RadioGroup
    private lateinit var radioInternetDisabled: RadioButton
    private lateinit var radioInternetBtLanFallback: RadioButton
    private lateinit var radioInternetAppOpen: RadioButton
    private lateinit var radioInternetAlways: RadioButton

    // Other views
    private lateinit var btnPairForInternet: Button
    private lateinit var checkBoxFixedIp: CheckBox
    private lateinit var layoutFixedIp: View
    private lateinit var etFixedIp: EditText
    private lateinit var tvCurrentWifi: TextView
    private lateinit var tvPhoneIp: TextView
    private lateinit var tvWatchWifi: TextView
    private lateinit var tvWatchIp: TextView

    // Internet server settings
    private lateinit var etSignalingUrl: EditText
    private lateinit var etStunUrl: EditText
    private lateinit var etTurnUrl: EditText
    private lateinit var etTurnUsername: EditText
    private lateinit var etTurnPassword: EditText

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
            runOnUiThread {
                updateWifiStatus()
                updateConnectionStatus()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService?.callback = null
            bluetoothService = null
            serviceBound = false
        }
    }

    private val serviceCallback = object : BluetoothService.ServiceCallback {
        override fun onStatusChanged(status: String) {
            runOnUiThread { updateConnectionStatus() }
        }
        override fun onDeviceConnected(deviceName: String) {
            runOnUiThread { updateConnectionStatus() }
        }
        override fun onDeviceDisconnected() {
            runOnUiThread { updateConnectionStatus() }
        }
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

        // Bind to BluetoothService
        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        initViews()
        loadSettings()
        setupListeners()
        updateWifiStatus()
        updatePairingButton()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Status views
        tvActiveConnection = findViewById(R.id.tvActiveConnection)
        indicatorBt = findViewById(R.id.indicatorBt)
        indicatorLan = findViewById(R.id.indicatorLan)
        indicatorInternet = findViewById(R.id.indicatorInternet)
        tvBtStatus = findViewById(R.id.tvBtStatus)
        tvLanStatus = findViewById(R.id.tvLanStatus)
        tvInternetStatus = findViewById(R.id.tvInternetStatus)

        // LAN Radio Group
        radioGroupLan = findViewById(R.id.radioGroupLan)
        radioLanDisabled = findViewById(R.id.radioLanDisabled)
        radioLanBtFallback = findViewById(R.id.radioLanBtFallback)
        radioLanAppOpen = findViewById(R.id.radioLanAppOpen)
        radioLanBtOrApp = findViewById(R.id.radioLanBtOrApp)
        radioLanAlways = findViewById(R.id.radioLanAlways)

        // Internet Radio Group
        radioGroupInternet = findViewById(R.id.radioGroupInternet)
        radioInternetDisabled = findViewById(R.id.radioInternetDisabled)
        radioInternetBtLanFallback = findViewById(R.id.radioInternetBtLanFallback)
        radioInternetAppOpen = findViewById(R.id.radioInternetAppOpen)
        radioInternetAlways = findViewById(R.id.radioInternetAlways)

        // Other views
        btnPairForInternet = findViewById(R.id.btnPairForInternet)
        checkBoxFixedIp = findViewById(R.id.checkBoxFixedIp)
        layoutFixedIp = findViewById(R.id.layoutFixedIp)
        etFixedIp = findViewById(R.id.etFixedIp)
        tvCurrentWifi = findViewById(R.id.tvCurrentWifi)
        tvPhoneIp = findViewById(R.id.tvPhoneIp)
        tvWatchWifi = findViewById(R.id.tvWatchWifi)
        tvWatchIp = findViewById(R.id.tvWatchIp)

        // Internet settings
        etSignalingUrl = findViewById(R.id.etSignalingUrl)
        etStunUrl = findViewById(R.id.etStunUrl)
        etTurnUrl = findViewById(R.id.etTurnUrl)
        etTurnUsername = findViewById(R.id.etTurnUsername)
        etTurnPassword = findViewById(R.id.etTurnPassword)
    }

    private fun loadSettings() {
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()

        // Load LAN rule (default: disabled)
        val lanRule = devicePrefs.getInt("wifi_activation_rule", 0)
        when {
            lanRule == 0 -> radioLanDisabled.isChecked = true
            (lanRule and BluetoothService.WIFI_RULE_ALWAYS) != 0 -> radioLanAlways.isChecked = true
            (lanRule and BluetoothService.WIFI_RULE_BT_OR_APP) != 0 -> radioLanBtOrApp.isChecked = true
            (lanRule and BluetoothService.WIFI_RULE_BT_FALLBACK) != 0 -> radioLanBtFallback.isChecked = true
            (lanRule and BluetoothService.WIFI_RULE_MAINACTIVITY) != 0 -> radioLanAppOpen.isChecked = true
            else -> radioLanDisabled.isChecked = true
        }

        // Load Internet rule (default: disabled)
        val internetRule = devicePrefs.getInt("internet_activation_rule", 0)
        when {
            internetRule == 0 -> radioInternetDisabled.isChecked = true
            (internetRule and TransportManager.INTERNET_RULE_ALWAYS) != 0 -> radioInternetAlways.isChecked = true
            (internetRule and TransportManager.INTERNET_RULE_BT_LAN_FALLBACK) != 0 -> radioInternetBtLanFallback.isChecked = true
            (internetRule and TransportManager.INTERNET_RULE_MAINACTIVITY) != 0 -> radioInternetAppOpen.isChecked = true
            else -> radioInternetDisabled.isChecked = true
        }

        // Load internet server settings
        etSignalingUrl.setText(devicePrefs.getString("internet_signaling_url", "ws://192.168.1.10:8080"))
        etStunUrl.setText(devicePrefs.getString("internet_stun_url", "stun:stun.cloudflare.com:3478"))
        etTurnUrl.setText(devicePrefs.getString("internet_turn_url", ""))
        etTurnUsername.setText(devicePrefs.getString("internet_turn_username", ""))
        etTurnPassword.setText(devicePrefs.getString("internet_turn_password", ""))

        // Load Fixed IP settings
        val fixedIpEnabled = devicePrefs.getBoolean("wifi_fixed_ip_enabled", false)
        val fixedIpAddress = devicePrefs.getString("wifi_fixed_ip_address", "")
        checkBoxFixedIp.isChecked = fixedIpEnabled
        etFixedIp.setText(fixedIpAddress)
        layoutFixedIp.visibility = if (fixedIpEnabled) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        // Pair button
        btnPairForInternet.setOnClickListener {
            startActivity(Intent(this, WiFiPairingActivity::class.java))
        }

        // LAN RadioGroup
        radioGroupLan.setOnCheckedChangeListener { _, _ ->
            saveLanRule()
        }

        // Internet RadioGroup
        radioGroupInternet.setOnCheckedChangeListener { _, _ ->
            saveInternetRule()
        }

        // Fixed IP
        checkBoxFixedIp.setOnCheckedChangeListener { _, isChecked ->
            layoutFixedIp.visibility = if (isChecked) View.VISIBLE else View.GONE
            devicePrefManager.getActiveDevicePrefs().edit()
                .putBoolean("wifi_fixed_ip_enabled", isChecked).apply()
            bluetoothService?.notifyWifiRuleChanged()
        }

        etFixedIp.addTextChangedListener(createTextWatcher { text ->
            devicePrefManager.getActiveDevicePrefs().edit()
                .putString("wifi_fixed_ip_address", text).apply()
            bluetoothService?.notifyWifiRuleChanged()
        })

        // Internet server settings
        etSignalingUrl.addTextChangedListener(createTextWatcher { text ->
            devicePrefManager.getActiveDevicePrefs().edit()
                .putString("internet_signaling_url", text).apply()
            bluetoothService?.notifyInternetSettingsChanged()
        })

        etStunUrl.addTextChangedListener(createTextWatcher { text ->
            devicePrefManager.getActiveDevicePrefs().edit()
                .putString("internet_stun_url", text).apply()
            bluetoothService?.notifyInternetSettingsChanged()
        })

        etTurnUrl.addTextChangedListener(createTextWatcher { text ->
            devicePrefManager.getActiveDevicePrefs().edit()
                .putString("internet_turn_url", text).apply()
            bluetoothService?.notifyInternetSettingsChanged()
        })

        etTurnUsername.addTextChangedListener(createTextWatcher { text ->
            devicePrefManager.getActiveDevicePrefs().edit()
                .putString("internet_turn_username", text).apply()
            bluetoothService?.notifyInternetSettingsChanged()
        })

        etTurnPassword.addTextChangedListener(createTextWatcher { text ->
            devicePrefManager.getActiveDevicePrefs().edit()
                .putString("internet_turn_password", text).apply()
            bluetoothService?.notifyInternetSettingsChanged()
        })
    }

    private fun createTextWatcher(onChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChanged(s.toString().trim())
            }
        }
    }

    private fun saveLanRule() {
        val newRule = when (radioGroupLan.checkedRadioButtonId) {
            R.id.radioLanDisabled -> 0
            R.id.radioLanBtFallback -> BluetoothService.WIFI_RULE_BT_FALLBACK
            R.id.radioLanAppOpen -> BluetoothService.WIFI_RULE_MAINACTIVITY
            R.id.radioLanBtOrApp -> BluetoothService.WIFI_RULE_BT_OR_APP
            R.id.radioLanAlways -> BluetoothService.WIFI_RULE_ALWAYS
            else -> 0
        }

        devicePrefManager.getActiveDevicePrefs().edit()
            .putInt("wifi_activation_rule", newRule).apply()

        bluetoothService?.syncWifiRuleToCompanion(newRule)
        bluetoothService?.notifyWifiRuleChanged()

        val ruleName = when (newRule) {
            0 -> "Disabled"
            BluetoothService.WIFI_RULE_BT_FALLBACK -> getString(R.string.network_rule_bt_fallback)
            BluetoothService.WIFI_RULE_MAINACTIVITY -> getString(R.string.network_rule_mainactivity)
            BluetoothService.WIFI_RULE_BT_OR_APP -> getString(R.string.network_rule_bt_or_app)
            BluetoothService.WIFI_RULE_ALWAYS -> getString(R.string.network_rule_always)
            else -> "Unknown"
        }
        Toast.makeText(this, "LAN: $ruleName", Toast.LENGTH_SHORT).show()
    }

    private fun saveInternetRule() {
        val newRule = when (radioGroupInternet.checkedRadioButtonId) {
            R.id.radioInternetDisabled -> 0
            R.id.radioInternetBtLanFallback -> TransportManager.INTERNET_RULE_BT_LAN_FALLBACK
            R.id.radioInternetAppOpen -> TransportManager.INTERNET_RULE_MAINACTIVITY
            R.id.radioInternetAlways -> TransportManager.INTERNET_RULE_ALWAYS
            else -> 0
        }

        devicePrefManager.getActiveDevicePrefs().edit()
            .putInt("internet_activation_rule", newRule).apply()

        bluetoothService?.notifyInternetSettingsChanged()
        bluetoothService?.notifyWifiRuleChanged()

        val ruleName = when (newRule) {
            0 -> "Disabled"
            TransportManager.INTERNET_RULE_BT_LAN_FALLBACK -> getString(R.string.network_rule_bt_lan_fallback)
            TransportManager.INTERNET_RULE_MAINACTIVITY -> getString(R.string.network_rule_mainactivity)
            TransportManager.INTERNET_RULE_ALWAYS -> getString(R.string.network_rule_always)
            else -> "Unknown"
        }
        Toast.makeText(this, "Internet: $ruleName", Toast.LENGTH_SHORT).show()
    }

    private fun updatePairingButton() {
        val mac = bluetoothService?.getConnectedDeviceMac()
        val isPaired = mac != null && bluetoothService?.isPairedWithCurrentDevice() == true

        btnPairForInternet.text = if (isPaired) {
            getString(R.string.wifi_already_paired)
        } else {
            getString(R.string.network_pair_for_internet)
        }
    }

    private fun updateConnectionStatus() {
        val btConnected = bluetoothService?.isConnected == true
        val lanConnected = bluetoothService?.isWifiConnected() == true
        val internetConnected = bluetoothService?.isInternetConnected() == true

        val lanRule = devicePrefManager.getActiveDevicePrefs().getInt("wifi_activation_rule", 0)
        val internetRule = devicePrefManager.getActiveDevicePrefs().getInt("internet_activation_rule", 0)

        // Determine LAN status: Connected, Disconnected (rule enabled but not connected), Standby (waiting for conditions), Disabled
        val lanShouldBeActive = when {
            lanRule == 0 -> false
            (lanRule and BluetoothService.WIFI_RULE_ALWAYS) != 0 -> true
            (lanRule and BluetoothService.WIFI_RULE_BT_OR_APP) != 0 -> !btConnected || true // app is open when this screen is shown
            (lanRule and BluetoothService.WIFI_RULE_BT_FALLBACK) != 0 -> !btConnected
            (lanRule and BluetoothService.WIFI_RULE_MAINACTIVITY) != 0 -> true // app is open
            else -> false
        }

        // Determine Internet status
        val internetShouldBeActive = when {
            internetRule == 0 -> false
            (internetRule and TransportManager.INTERNET_RULE_ALWAYS) != 0 -> true
            (internetRule and TransportManager.INTERNET_RULE_BT_LAN_FALLBACK) != 0 -> !btConnected && !lanConnected
            (internetRule and TransportManager.INTERNET_RULE_MAINACTIVITY) != 0 -> true // app is open
            else -> false
        }

        // Update BT indicator
        indicatorBt.setBackgroundResource(
            if (btConnected) R.drawable.status_indicator_on else R.drawable.status_indicator_disconnected
        )
        tvBtStatus.text = if (btConnected) "Connected" else "Disconnected"

        // Update LAN indicator
        indicatorLan.setBackgroundResource(
            when {
                lanConnected -> R.drawable.status_indicator_on
                lanRule == 0 -> R.drawable.status_indicator_off
                lanShouldBeActive -> R.drawable.status_indicator_disconnected  // Should be on but isn't
                else -> R.drawable.status_indicator_standby  // Waiting for conditions
            }
        )
        tvLanStatus.text = when {
            lanConnected -> "Connected"
            lanRule == 0 -> "Disabled"
            lanShouldBeActive -> "Disconnected"
            else -> "Paused by rule"
        }

        // Update Internet indicator
        indicatorInternet.setBackgroundResource(
            when {
                internetConnected -> R.drawable.status_indicator_on
                internetRule == 0 -> R.drawable.status_indicator_off
                internetShouldBeActive -> R.drawable.status_indicator_disconnected
                else -> R.drawable.status_indicator_standby
            }
        )
        tvInternetStatus.text = when {
            internetConnected -> "Connected"
            internetRule == 0 -> "Disabled"
            internetShouldBeActive -> "Disconnected"
            else -> "Paused by rule"
        }

        // Update active connection summary using TransportManager
        val statusString = bluetoothService?.transportManager?.getConnectionStatusString() ?: "Disconnected"
        tvActiveConnection.text = statusString
        val isAnyConnected = bluetoothService?.transportManager?.isAnyTransportConnected() == true
        tvActiveConnection.setTextColor(
            if (isAnyConnected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        )
    }

    private fun updateWifiStatus() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wm.isWifiEnabled) {
            val info = wm.connectionInfo
            if (info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED) {
                val ssid = info.ssid.replace("\"", "")
                tvCurrentWifi.text = if (ssid == "<unknown ssid>") "Connected" else ssid

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
            tvCurrentWifi.text = "Disabled"
            tvPhoneIp.text = "N/A"
        }
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
        updatePairingButton()
        updateConnectionStatus()
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
                updateConnectionStatus()
                delay(2000)
            }
        }
    }

    private fun stopWatchStatusPolling() {
        watchStatusPollingJob?.cancel()
        watchStatusPollingJob = null
    }
}
