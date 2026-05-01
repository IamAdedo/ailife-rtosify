package com.iamadedo.watchapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val deviceType = prefs.getString("device_type", null)

            // Só inicia automaticamente se o usuário já tiver configurado o app E o serviço estiver ativado
            val isServiceEnabled = prefs.getBoolean("service_enabled", true)
            val isDynamicIsland = prefs.getString("notification_style", "android") == "dynamic_island"

            if (deviceType != null && isServiceEnabled) {
                val serviceIntent = Intent(context, BluetoothService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // No Android 8+, iniciar em background tem restrições,
                    // mas startForegroundService é permitido no Boot Receiver
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            // Auto-start Dynamic Island if enabled
            if (isDynamicIsland) {
                val intentDI = Intent(context, DynamicIslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intentDI)
                } else {
                    context.startService(intentDI)
                }
            }
        }
    }
}