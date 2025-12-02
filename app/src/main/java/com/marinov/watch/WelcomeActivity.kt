package com.marinov.watch

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
import com.google.android.material.card.MaterialCardView
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
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkDndPermissionAndFinish()
        }

    private val dndPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishSetup("WATCH")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        checkBatteryOptimizationDirect()
        checkAndRequestPermissions()

        val cardSmartphone = findViewById<MaterialCardView>(R.id.cardSmartphone)
        val cardWatch = findViewById<MaterialCardView>(R.id.cardWatch)

        cardSmartphone.setOnClickListener { finishSetup("PHONE") }
        cardWatch.setOnClickListener {
            // 1. Inicia com a verificação de Root
            checkRootAndSetup()
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
                        .setTitle("Permissão necessária.")
                        .setMessage("Para que seja possível exibir o SSID da rede Wi-Fi conectada ao relógio inteligente, é necessário permissão de localização avançada. Para isso, selecione 'Pemitir o tempo todo' na tela a seguir.")
                        .setPositiveButton("Entendi") { _, _ ->
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        .setNegativeButton("Agora não", null)
                        .show()
                } else {
                    // Android 10 pede direto no popup
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        }
    }

    // 2. Faz a verificação de Root e DEPOIS chama a permissão de instalação.
    private fun checkRootAndSetup() {
        lifecycleScope.launch(Dispatchers.IO) {
            checkRootAccess() // Essa chamada dispara o prompt de root (se houver magisk/su)

            withContext(Dispatchers.Main) {
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