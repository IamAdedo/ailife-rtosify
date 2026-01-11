package com.ailife.rtosify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            
            // Only start automatically if autostart is enabled
            // RTOSify is phone-only so no device_type check needed
            val autostartEnabled = prefs.getBoolean("autostart_on_boot", true)
            if (autostartEnabled) {
                val serviceIntent = Intent(context, BluetoothService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // No Android 8+, iniciar em background tem restrições,
                    // mas startForegroundService é permitido no Boot Receiver
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}