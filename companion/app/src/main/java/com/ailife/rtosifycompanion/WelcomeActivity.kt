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
            // Verifica se a localização "durante o uso" foi concedida
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

            if (fineLocationGranted) {
                // Se deu permissão básica, agora tentamos pedir a permissão "O Tempo Todo"
                checkAndRequestBackgroundLocation()
            }
        }
    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkBluetoothAndStartSetup()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startAutomaticSetup()
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkDndPermissionAndFinish()
        }

    private val dndPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishSetup("WATCH")
        }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                android.util.Log.d("Welcome", "Bond state changed: ${device?.name} -> $bondState")

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    android.util.Log.d("Welcome", "Device paired successfully! Continuing setup...")
                    // Device was successfully paired, proceed with setup
                    unregisterReceiver(this)
                    checkRootAndSetup()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Register pairing receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(pairingReceiver, filter)

        checkBatteryOptimizationDirect()
        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pairingReceiver)
        } catch (_: Exception) {}
    }

    private fun checkBluetoothAndStartSetup() {
        val btManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter

        if (adapter == null) {
            // Device doesn't support Bluetooth
            android.widget.Toast.makeText(this, "This device doesn't support Bluetooth", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            // Request to enable Bluetooth
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            startAutomaticSetup()
        }
    }

    private fun startAutomaticSetup() {
        lifecycleScope.launch {
            val qrImageView = findViewById<ImageView>(R.id.imgQrCode)
            val progressBar = findViewById<android.view.View>(R.id.progressBarSetup)
            val statusText = findViewById<android.widget.TextView>(R.id.tvWelcomeStatus)

            statusText.text = "Scan this QR code on your phone"
            progressBar.visibility = android.view.View.GONE

            val btManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = btManager.adapter

            if (adapter == null || !adapter.isEnabled) {
                statusText.text = "Bluetooth is disabled. Please enable it."
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

                statusText.text = "Scan QR Code on your phone\nWaiting for pairing..."
            } else {
                statusText.text = "Failed to generate QR code"
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

    // Lógica para solicitar Localização em Background ("O Tempo Todo")
    private fun checkAndRequestBackgroundLocation() {
        // Background Location só existe no Android 10 (Q) ou superior
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackground) {
                // No Android 11+ (R), é recomendável explicar ao usuário antes de pedir,
                // pois o sistema irá redirecionar para as Configurações.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.welcome_permission_title))
                        .setMessage(getString(R.string.welcome_permission_message))
                        .setPositiveButton(getString(R.string.welcome_permission_understood)) { _, _ ->
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        .setNegativeButton(getString(R.string.welcome_permission_not_now), null)
                        .show()
                } else {
                    // Android 10 pede direto no popup
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            } else {
                checkBluetoothAndStartSetup()
            }
        } else {
            checkBluetoothAndStartSetup()
        }
    }

    // 2. Faz a verificação de Root e DEPOIS chama a permissão de instalação.
    private fun checkRootAndSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            android.util.Log.d("Welcome", "Starting root check...")
            checkRootAccess() // Essa chamada dispara o prompt de root (se houver magisk/su)

            withContext(Dispatchers.Main) {
                android.util.Log.d("Welcome", "Root check done, requesting install permission...")
                // Após o prompt de root ser tratado, pedimos a permissão de instalação
                requestInstallPermission()
            }
        }
    }

    // 3. Chamado após a verificação de root.
    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = "package:$packageName".toUri()
            installPermissionLauncher.launch(intent)
        } else {
            checkDndPermissionAndFinish()
        }
    }

    // 4. Nova verificação para Não Perturbe (DND)
    private fun checkDndPermissionAndFinish() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            dndPermissionLauncher.launch(intent)
        } else {
            finishSetup("WATCH")
        }
    }

    private suspend fun checkRootAccess(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val result = process.waitFor()
                result == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    // 5. Finaliza o processo
    private fun finishSetup(type: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit { putString("device_type", type) }
        startActivity(Intent(this, MainActivity::class.java))
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

        // Notificações no Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filtra o que falta
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            // Se já tem as básicas, checa se falta a Background
            checkAndRequestBackgroundLocation()
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimizationDirect() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}