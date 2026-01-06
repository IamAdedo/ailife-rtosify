package com.ailife.rtosify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class WelcomeActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            showPairingOptions()
        }
    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            showPairingOptions()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            showPairingOptions()
        }

    private val dndPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishSetup("PHONE")
        }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val qrData = result.contents
            android.util.Log.d("Welcome", "QR Scanned: $qrData")

            // Check if it's the new format (CODE-ANDROIDID) or old format (MAC address)
            val macRegex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")

            if (macRegex.matches(qrData)) {
                // Old format: direct MAC address
                if (qrData == "02:00:00:00:00:00") {
                    Toast.makeText(this, R.string.welcome_invalid_mac, Toast.LENGTH_LONG).show()
                } else {
                    startPairingWithDevice(qrData)
                }
            } else if (qrData.contains("-")) {
                // New format: CODE-ANDROIDID
                val parts = qrData.split("-", limit = 2)
                if (parts.size == 2) {
                    val pairingCode = parts[0]
                    val androidId = parts[1]
                    android.util.Log.d("Welcome", "Pairing code: $pairingCode, Android ID: $androidId")
                    startDiscoveryForPairingCode(pairingCode, androidId)
                } else {
                    Toast.makeText(this, R.string.welcome_invalid_qr_format, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.welcome_invalid_qr_data, qrData), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                android.util.Log.d("Welcome", "Bond state changed for ${device?.address}: $bondState")

                val targetMac = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("temp_mac", null)
                if (device != null && device.address == targetMac) {
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            android.util.Log.d("Welcome", "Device bonded successfully")
                            completeSetupWithDeviceAddress(device.address)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            android.util.Log.w("Welcome", "Pairing failed or cancelled")
                            findViewById<TextView>(R.id.tvWelcomeStatus).text = getString(R.string.welcome_pairing_failed)
                            findViewById<android.view.View>(R.id.progressBarSetup).visibility = android.view.View.GONE
                            findViewById<android.view.View>(R.id.btnRetry).visibility = android.view.View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val deviceName = device?.name
                    android.util.Log.d("Welcome", "Found device: $deviceName (${device?.address})")

                    val targetCode = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("temp_pairing_code", null)
                    if (deviceName != null && targetCode != null && deviceName == "rtosify-$targetCode") {
                        android.util.Log.d("Welcome", "Found matching device!")

                        // Stop discovery
                        val btManager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                        try {
                            btManager.adapter.cancelDiscovery()
                        } catch (_: SecurityException) {}

                        // Start pairing
                        startPairingWithDevice(device.address)
                    }
                }
                android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    android.util.Log.d("Welcome", "Discovery finished")
                    val targetCode = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("temp_pairing_code", null)
                    if (targetCode != null) {
                        // Device not found
                        findViewById<TextView>(R.id.tvWelcomeStatus).text = getString(R.string.welcome_watch_not_found)
                        findViewById<android.view.View>(R.id.progressBarSetup).visibility = android.view.View.GONE
                        findViewById<android.view.View>(R.id.btnRetry).visibility = android.view.View.VISIBLE
                        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit { remove("temp_pairing_code") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        findViewById<android.widget.Button>(R.id.btnRetry).setOnClickListener {
            startAutomaticSetup()
        }

        findViewById<android.widget.Button>(R.id.btnScanQr).setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt(getString(R.string.welcome_qr_scan_instruction))
            options.setBeepEnabled(true)
            options.setOrientationLocked(false)
            qrScannerLauncher.launch(options)
        }

        checkAndRequestPermissions()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAutomaticSetup() {
        lifecycleScope.launch {
            val statusText = findViewById<TextView>(R.id.tvWelcomeStatus)
            val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
            val btnRetry = findViewById<android.view.View>(R.id.btnRetry)

            progressBar.visibility = android.view.View.VISIBLE
            btnRetry.visibility = android.view.View.GONE

            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            prefs.edit { putString("device_type", "PHONE") }

            val btManager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = btManager.adapter

            if (adapter == null || !adapter.isEnabled) {
                statusText.text = getString(R.string.welcome_bt_disabled)
                progressBar.visibility = android.view.View.GONE
                btnRetry.visibility = android.view.View.VISIBLE
                return@launch
            }

            statusText.text = getString(R.string.welcome_searching)
            
            // Wait a bit to ensure UI updates
            delay(1000)

            // Tenta encontrar um relógio já pareado
            val bondedDevices = try {
                adapter.bondedDevices.toList()
            } catch (e: SecurityException) {
                android.util.Log.e("Welcome", "SecurityException access bonded devices", e)
                emptyList()
            }

            android.util.Log.d("Welcome", "Bonded devices found: ${bondedDevices.size}")

            if (bondedDevices.isNotEmpty()) {
                // Filtra possíveis relógios
                val candidates = bondedDevices.filter { 
                    val name = it.name ?: ""
                    name.contains("Watch", ignoreCase = true) || 
                    name.contains("Marinov", ignoreCase = true) ||
                    name.contains("rtosify", ignoreCase = true) ||
                    name.contains("Companion", ignoreCase = true)
                }

                if (candidates.size == 1) {
                    // Se houver apenas UM candidato provável, usa ele direto
                    val watch = candidates.first()
                    android.util.Log.d("Welcome", "Automatic match: ${watch.name}")
                    completeSetupWithDevice(watch)
                } else {
                    // Se houver vários ou nenhum óbvio, deixa o usuário escolher
                    progressBar.visibility = android.view.View.GONE
                    btnRetry.visibility = android.view.View.VISIBLE
                    
                    val devicesToShow = if (candidates.isNotEmpty()) candidates else bondedDevices
                    val names = devicesToShow.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
                    
                    AlertDialog.Builder(this@WelcomeActivity)
                        .setTitle(R.string.dialog_select_watch_title)
                        .setItems(names) { _, which ->
                            completeSetupWithDevice(devicesToShow[which])
                        }
                        .setNegativeButton(R.string.dialog_upload_apk_cancel) { _, _ ->
                            statusText.text = getString(R.string.welcome_selection_cancelled)
                        }
                        .show()
                }
            } else {
                android.util.Log.w("Welcome", "No bonded devices found")
                statusText.text = getString(R.string.welcome_no_paired_found)
                progressBar.visibility = android.view.View.GONE
                btnRetry.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun completeSetupWithDevice(device: android.bluetooth.BluetoothDevice) {
        completeSetupWithDeviceAddress(device.address)
    }

    private fun completeSetupWithDeviceAddress(address: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val statusText = findViewById<TextView>(R.id.tvWelcomeStatus)
        val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
        
        prefs.edit { 
            putString("last_mac", address)
            remove("temp_mac")
        }
        
        statusText.text = getString(R.string.welcome_watch_ready)
        progressBar.visibility = android.view.View.VISIBLE
        
        lifecycleScope.launch {
            delay(1500)
            finishSetup("PHONE")
        }
    }

    private fun startDiscoveryForPairingCode(pairingCode: String, androidId: String) {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled. Please enable it and try again.", Toast.LENGTH_LONG).show()
            return
        }

        val statusText = findViewById<TextView>(R.id.tvWelcomeStatus)
        val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
        val btnRetry = findViewById<android.view.View>(R.id.btnRetry)

        android.util.Log.d("Welcome", "Starting discovery for pairing code: $pairingCode")

        // Save pairing code to identify device in receiver
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            putString("temp_pairing_code", pairingCode)
            putString("temp_android_id", androidId)
        }

        // Register discovery receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)

        // Also register bond state receiver for pairing
        val bondFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, bondFilter)

        statusText.text = getString(R.string.welcome_searching_qr)
        progressBar.visibility = android.view.View.VISIBLE
        btnRetry.visibility = android.view.View.GONE

        // Start discovery
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val started = adapter.startDiscovery()
            if (!started) {
                android.util.Log.e("Welcome", "Failed to start discovery")
                statusText.text = getString(R.string.welcome_discovery_error)
                progressBar.visibility = android.view.View.GONE
                btnRetry.visibility = android.view.View.VISIBLE
            }
        } catch (e: SecurityException) {
            android.util.Log.e("Welcome", "SecurityException starting discovery", e)
            statusText.text = getString(R.string.welcome_bt_permission_denied)
            progressBar.visibility = android.view.View.GONE
            btnRetry.visibility = android.view.View.VISIBLE
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startPairingWithDevice(mac: String) {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled. Please enable it and try again.", Toast.LENGTH_LONG).show()
            return
        }

        val device = adapter.getRemoteDevice(mac)
        val statusText = findViewById<TextView>(R.id.tvWelcomeStatus)
        val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
        val btnRetry = findViewById<android.view.View>(R.id.btnRetry)

        android.util.Log.d("Welcome", "Starting pairing with $mac, current bond state: ${device.bondState}")

        // Clear discovery code and save MAC
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            putString("temp_mac", mac)
            remove("temp_pairing_code")
        }

        // Register receiver if not already
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        try {
            registerReceiver(bondStateReceiver, filter)
        } catch (_: Exception) {
            // Already registered
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            android.util.Log.d("Welcome", "Device already bonded")
            completeSetupWithDeviceAddress(mac)
        } else {
            statusText.text = getString(R.string.welcome_pairing_info)
            progressBar.visibility = android.view.View.VISIBLE
            btnRetry.visibility = android.view.View.GONE

            val initiated = device.createBond()
            if (!initiated) {
                android.util.Log.e("Welcome", "Failed to initiate pairing")
                statusText.text = getString(R.string.welcome_pairing_start_error)
                progressBar.visibility = android.view.View.GONE
                btnRetry.visibility = android.view.View.VISIBLE
            }
        }
    }

    // Simplified setup flow: Bluetooth/Location Scan -> QR Scanner -> PermissionActivity

    private fun checkBluetoothAndShowPairingOptions() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter

        if (adapter == null) {
            Toast.makeText(this, R.string.welcome_bt_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            showPairingOptions()
        }
    }

    private fun showPairingOptions() {
        val statusText = findViewById<TextView>(R.id.tvWelcomeStatus)
        val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
        val btnRetry = findViewById<android.widget.Button>(R.id.btnRetry)
        val btnScanQr = findViewById<android.widget.Button>(R.id.btnScanQr)

        statusText.text = getString(R.string.welcome_pairing_choice)
        progressBar.visibility = android.view.View.GONE
        btnRetry.visibility = android.view.View.VISIBLE
        btnRetry.text = getString(R.string.welcome_manual_selection)
        btnScanQr.visibility = android.view.View.VISIBLE
    }

    // Methods removed as they are now handled in PermissionActivity

    // 5. Finaliza o processo
    private fun finishSetup(type: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit().putString("device_type", type).commit()
        
        // After setup, show PermissionActivity to handle other permissions
        val intent = Intent(this, PermissionActivity::class.java).apply {
            putExtra("from_setup", true)
        }
        startActivity(intent)
        finish()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // Solicita permissões básicas (Foreground)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Permissões de Bluetooth específicas do Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        // Filtra o que falta
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            showPairingOptions()
        }
    }

    // Battery optimization check removed from here, will be in PermissionActivity
}