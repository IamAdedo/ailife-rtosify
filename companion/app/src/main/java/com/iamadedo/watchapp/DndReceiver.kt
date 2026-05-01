package com.iamadedo.watchapp

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DndReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DND_ON = "com.iamadedo.watchapp.ACTION_DND_ON"
        const val ACTION_DND_OFF = "com.iamadedo.watchapp.ACTION_DND_OFF"
        private const val TAG = "DndReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.e(TAG, "Notification policy access not granted!")
            return
        }

        when (intent.action) {
            ACTION_DND_ON -> {
                Log.d(TAG, "Enabling DND via alarm")
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                notifyService(context, true)
            }
            ACTION_DND_OFF -> {
                Log.d(TAG, "Disabling DND via alarm")
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                notifyService(context, false)
            }
        }
    }

    private fun notifyService(context: Context, enabled: Boolean) {
        val serviceIntent = Intent(context, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.ACTION_DND_UPDATED"
            putExtra("enabled", enabled)
        }
        context.startService(serviceIntent)
    }
}
