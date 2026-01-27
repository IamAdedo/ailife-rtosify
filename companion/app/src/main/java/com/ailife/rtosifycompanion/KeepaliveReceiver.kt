package com.ailife.rtosifycompanion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class KeepaliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_KEEPALIVE_TRIGGER = "com.ailife.rtosifycompanion.ACTION_KEEPALIVE_TRIGGER"
        private const val TAG = "KeepaliveReceiver"
        private const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, KeepaliveReceiver::class.java).apply {
                action = ACTION_KEEPALIVE_TRIGGER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Keepalive scheduled for 5 minutes from now")
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, KeepaliveReceiver::class.java).apply {
                action = ACTION_KEEPALIVE_TRIGGER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
                Log.d(TAG, "Keepalive cancelled")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_KEEPALIVE_TRIGGER) {
            Log.d(TAG, "Keepalive triggered - Restarting services")
            
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("aggressive_keepalive_enabled", false)
            
            if (isEnabled) {
                // 1. Ensure Bluetooth Service is running
                if (prefs.getBoolean("service_enabled", true)) {
                    try {
                        val btIntent = Intent(context, BluetoothService::class.java)
                        ContextCompat.startForegroundService(context, btIntent)
                        Log.d(TAG, "Sent startForegroundService for BluetoothService")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart BluetoothService: ${e.message}")
                    }
                }

                // 2. Ensure Dynamic Island Service is running
                val notifStyle = prefs.getString("notification_style", "android")
                if (notifStyle == "dynamic_island") {
                    try {
                        val diIntent = Intent(context, DynamicIslandService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(diIntent)
                        } else {
                            context.startService(diIntent)
                        }
                        Log.d(TAG, "Sent startService for DynamicIslandService")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart DynamicIslandService: ${e.message}")
                    }
                }

                // 3. Reschedule
                schedule(context)
            }
        } else if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
             val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
             if (prefs.getBoolean("aggressive_keepalive_enabled", false)) {
                 schedule(context)
             }
        }
    }
}


