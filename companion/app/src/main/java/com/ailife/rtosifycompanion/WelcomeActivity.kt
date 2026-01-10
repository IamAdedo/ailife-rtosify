package com.ailife.rtosifycompanion

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WelcomeActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            startAutomaticSetup()
        }
    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startAutomaticSetup()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startAutomaticSetup()
        }

    private val dndPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishSetup("WATCH")
        }

    private val pairingReceiver = object : BroadcastReceiver() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                android.util.Log.d("Welcome", "Bond state changed: ${device?.name} -> $bondState")

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    android.util.Log.d("Welcome", "Device paired successfully! Continuing setup...")
                    // Device was successfully paired, proceed with setup
                    unregisterReceiver(this)
                    finishSetup("WATCH")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Register pairing receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, pairingReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        if (resources.configuration.isScreenRound) {
            showRoundScreenWarning()
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun showRoundScreenWarning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.round_screen_warning_title)
            .setMessage(R.string.round_screen_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.round_screen_warning_continue) { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton(R.string.round_screen_warning_exit) { _, _ ->
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pairingReceiver)
        } catch (_: Exception) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAutomaticSetup() {
        lifecycleScope.launch {
            val qrImageView = findViewById<ImageView>(R.id.imgQrCode)
            val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
            val statusText = findViewById<android.widget.TextView>(R.id.tvWelcomeStatus)
            val btnAlreadyPaired = findViewById<android.widget.Button>(R.id.btnAlreadyPaired)

            statusText.text = getString(R.string.welcome_qr_scan_instruction)
            progressBar.visibility = android.view.View.GONE

            btnAlreadyPaired.visibility = android.view.View.VISIBLE
            btnAlreadyPaired.setOnClickListener {
                finishSetup("WATCH")
            }

            val btManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = btManager.adapter

            if (adapter == null || !adapter.isEnabled) {
                statusText.text = getString(R.string.welcome_bt_disabled)
                return@launch
            }

            // Get unique device identifier using Android ID
            val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)

            // Get or create a persistent pairing code
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            var pairingCode = prefs.getString("pairing_code", null)
            if (pairingCode == null) {
                // Generate a 6-character alphanumeric code
                pairingCode = generatePairingCode()
                prefs.edit { putString("pairing_code", pairingCode) }
            }

            // Set Bluetooth device name to include the pairing code
            val originalName = adapter.name
            val newName = "rtosify-$pairingCode"
            try {
                adapter.name = newName
                android.util.Log.d("Welcome", "Bluetooth name set to: $newName")
            } catch (e: SecurityException) {
                android.util.Log.e("Welcome", "Cannot set Bluetooth name", e)
            }

            // Make device discoverable for 300 seconds (5 minutes)
            try {
                val discoverableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                startActivity(discoverableIntent)
                android.util.Log.d("Welcome", "Requested device to be discoverable")
            } catch (e: Exception) {
                android.util.Log.e("Welcome", "Cannot make device discoverable", e)
            }

            // Create QR data with pairing code and Android ID
            // Format: CODE-ANDROIDID
            val qrData = "$pairingCode-$androidId"

            android.util.Log.d("Welcome", "Pairing Code: $pairingCode")
            android.util.Log.d("Welcome", "Android ID: $androidId")
            android.util.Log.d("Welcome", "QR Data: $qrData")

            val qrBitmap = generateQrCode(qrData)
            if (qrBitmap != null) {
                qrImageView.setImageBitmap(qrBitmap)
                qrImageView.visibility = android.view.View.VISIBLE

                // Hide the manual finish button - it will auto-proceed when paired
                val btnFinished = findViewById<android.widget.Button>(R.id.btnFinishedSetup)
                btnFinished.visibility = android.view.View.GONE

                statusText.text = getString(R.string.welcome_pairing_waiting)
            } else {
                statusText.text = getString(R.string.welcome_qr_gen_error)
            }
        }
    }

    private fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Avoid confusing characters
        return (1..6)
            .map { chars.random() }
            .joinToString("")
    }

    private fun generateQrCode(text: String): Bitmap? {
        val writer = QRCodeWriter()
        try {
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            return bmp
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Simplified setup flow: Bluetooth -> QR -> Finish

    // Methods removed as they are now handled in PermissionActivity

    // 5. Finishes the process
    private fun finishSetup(type: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit { putString("device_type", type) }
        
        // After paired, show PermissionActivity to handle other permissions
        val intent = Intent(this, PermissionActivity::class.java).apply {
            putExtra("from_setup", true)
        }
        startActivity(intent)
        finish()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // Request location permissions only on versions where it's required for pairing/scan (Android 11-)
        // On Android 12+ we use the 'neverForLocation' flag in the manifest, so location is optional for initial setup.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Permissões de Bluetooth específicas do Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        // Filter what's missing
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            startAutomaticSetup()
        }
    }

    // Battery optimization check removed from here, will be in PermissionActivity
}